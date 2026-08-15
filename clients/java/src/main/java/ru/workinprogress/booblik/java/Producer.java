package ru.workinprogress.booblik.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accumulates records and sends them in batches.
 *
 * <p><b>It owns its Connection</b>: one thread holds the pending records and is the only writer to
 * that socket. Do not use the same Connection directly while a Producer has it — responses are
 * matched in order, and a second writer takes somebody else's answer.
 *
 * <p>Records for different partitions accumulate separately and go out as separate requests, because
 * a request addresses one partition — a partition being what has one writer.
 */
public final class Producer implements AutoCloseable {

    /**
     * What a future carries when the batch went out under {@link AckPolicy#NONE}. The record was
     * sent; no offset exists, because none is assigned until the writer reaches the batch.
     */
    public static final long OFFSET_UNKNOWN = -1;

    private final Connection connection;
    private final ProducerConfig config;
    private final BlockingQueue<Command> mailbox = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread loop;

    public Producer(Connection connection, ProducerConfig config) {
        this.connection = connection;
        this.config = config;
        this.loop = new Thread(this::run, "booblik-producer");
        this.loop.setDaemon(true);
        this.loop.start();
    }

    /**
     * Queues a record and returns where its offset will arrive.
     *
     * <p>The record is not on the wire when this returns — that is the point. Await the future to
     * know it landed, or call {@link #flush()} to push everything queued.
     */
    public CompletableFuture<Long> send(String topic, int partition, byte[] record) {
        if (closed.get()) {
            throw new IllegalStateException("booblik: producer is closed");
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        mailbox.add(new Command(topic, partition, record, future, null));
        return future;
    }

    /** Sends everything queued and waits for the broker to answer all of it. */
    public void flush() {
        CountDownLatch done = new CountDownLatch(1);
        mailbox.add(new Command(null, 0, null, null, done));
        try {
            done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("booblik: interrupted while flushing", interrupted);
        }
    }

    /** Flushes what is queued and stops the accumulator. Does not close the Connection. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        mailbox.add(Command.CLOSE);
        try {
            loop.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        failLeftovers();
    }

    /**
     * Fails anything that reached the mailbox after the loop had already returned.
     *
     * <p>A {@code send} that passed the closed check just before {@code close} ran can queue its
     * record after the loop is gone, and it would then wait on a future nothing will ever complete.
     * Failing those is the difference between a caller that learns something and a caller that
     * stops.
     *
     * <p>The Go client had the same race with a worse ending — it closed its channel, so the late
     * send panicked outright — and that is how this was noticed at all.
     */
    // Package-private, not private: `ProducerTest` reaches past the public closed check to exercise
    // this path directly. The race it guards against has a window too narrow for threads to hit
    // reliably — a hammering test of it in the Python client passed just as happily with the
    // handling removed — so the honest test is a direct one.
    void failLeftovers() {
        Command leftover;
        while ((leftover = mailbox.poll()) != null) {
            if (leftover.future != null) {
                leftover.future.completeExceptionally(new IllegalStateException("booblik: producer is closed"));
            }
            if (leftover.flush != null) {
                leftover.flush.countDown();
            }
        }
    }

    /** Test-only: queues a record past the public closed check, as a racing caller would. */
    void enqueuePastTheCheck(String topic, int partition, byte[] record, CompletableFuture<Long> future) {
        mailbox.add(new Command(topic, partition, record, future, null));
    }

    private void run() {
        Map<BatchKey, Batch> pending = new HashMap<>();
        long deadline = 0;

        while (true) {
            Command command;
            try {
                if (pending.isEmpty()) {
                    command = mailbox.take();
                } else {
                    long remaining = deadline - System.nanoTime();
                    // `poll` with a timeout either returns an item or null. It cannot take an item
                    // off the queue and then drop it, which is what the equivalent on the JVM's
                    // coroutine client did twice — a timeout there cancels the pending receive, and
                    // a cancelled receive could swallow a record, after which whoever awaited its
                    // offset waited for ever.
                    command = mailbox.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                deliver(pending);
                return;
            }

            if (command == null) {
                deliver(pending);
                continue;
            }
            if (command == Command.CLOSE) {
                // Close is a flush: whatever the loop already accepted still goes out. Dropping it
                // would make every clean shutdown a silent data loss.
                deliver(pending);
                return;
            }
            if (command.flush != null) {
                deliver(pending);
                command.flush.countDown();
                continue;
            }

            BatchKey key = new BatchKey(command.topic, command.partition);
            Batch batch = pending.computeIfAbsent(key, ignored -> new Batch());
            batch.records.add(command.record);
            batch.waiting.add(command.future);

            // The window is measured from the **first** record of the batch and never restarted.
            // Timing from the last would let a steady trickle postpone the send indefinitely,
            // turning a latency bound into a latency hope.
            if (pending.size() == 1 && batch.records.size() == 1) {
                deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.lingerMillis());
            }
            if (batch.records.size() >= config.maxBatchSize()) {
                deliver(pending);
            }
        }
    }

    private void deliver(Map<BatchKey, Batch> pending) {
        if (pending.isEmpty()) {
            return;
        }

        List<Map.Entry<BatchKey, Batch>> batches = new ArrayList<>(pending.entrySet());
        pending.clear();

        for (Map.Entry<BatchKey, Batch> entry : batches) {
            BatchKey key = entry.getKey();
            Batch batch = entry.getValue();
            try {
                ProduceResult result =
                        connection.produce(key.topic(), key.partition(), batch.records, config.ack());
                for (int index = 0; index < batch.waiting.size(); index++) {
                    // One request is written by one call, so the records are contiguous.
                    batch.waiting
                            .get(index)
                            .complete(result == null ? OFFSET_UNKNOWN : result.baseOffset() + index);
                }
            } catch (RuntimeException failure) {
                // A batch that fails fails for all of its records. Completing some and abandoning
                // the rest would leave callers awaiting a future nothing will ever finish.
                for (CompletableFuture<Long> waiting : batch.waiting) {
                    waiting.completeExceptionally(failure);
                }
            }
        }
    }

    private record BatchKey(String topic, int partition) {}

    private static final class Batch {
        private final List<byte[]> records = new ArrayList<>();
        private final List<CompletableFuture<Long>> waiting = new ArrayList<>();
    }

    private static final class Command {

        private static final Command CLOSE = new Command(null, 0, null, null, null);

        private final String topic;
        private final int partition;
        private final byte[] record;
        private final CompletableFuture<Long> future;
        private final CountDownLatch flush;

        Command(
                String topic,
                int partition,
                byte[] record,
                CompletableFuture<Long> future,
                CountDownLatch flush) {
            this.topic = topic;
            this.partition = partition;
            this.record = record;
            this.future = future;
            this.flush = flush;
        }
    }
}
