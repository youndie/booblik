package ru.workinprogress.booblik.java;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Reads one partition of one topic, forward, from wherever it is told to start.
 *
 * <p><b>The position lives here, not in the broker.</b> That is half the reason this project has no
 * consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
 * already knows, and asking a broker to remember it is what drags in cluster consensus. The cost is
 * that a restarting consumer has to be told where to resume — {@link #position()} is the number to
 * write down, and writing it down <em>after</em> the records are dealt with rather than before is
 * what makes a restart re-deliver instead of skip.
 *
 * <p><b>Not safe for concurrent use.</b> Every {@link #poll()} advances the position, and the
 * connection matches responses to requests in the order they were sent. One consumer, one partition,
 * one thread.
 *
 * <p>Iterating is the plain form:
 *
 * <pre>{@code
 * for (byte[] record : consumer) {
 *     handle(record);
 * }
 * }</pre>
 *
 * <p><b>The loop does not end</b>, and that is the shape of the thing rather than an oversight: a
 * partition has no end, only a place it has not been written to yet. {@code break} out of it, or
 * close the connection, which ends the iteration with the failure that the socket reports.
 *
 * <p>An {@link Iterable} and not a callback: this way the loop belongs to the caller, {@code break}
 * and {@code return} work, and an exception lands in the caller's own handler. The Kotlin client
 * offers a {@code Flow} instead, which is the same idea with suspension — and is exactly the part
 * that could not be exposed to Java, which is why this client exists at all.
 */
public final class Consumer implements Iterable<byte[]> {

    /**
     * 1 MiB: large enough that a fetch is worth its round trip, small enough that one response
     * cannot dominate a small heap. Every client in this repository uses the same number.
     */
    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    /**
     * Five seconds. A caught-up consumer with no wait asks again immediately and gets nothing, which
     * is a busy loop dressed as a poll — measured at about two thousand pointless requests a second
     * (benchmarking, measurement 24). Waiting costs new records nothing: the broker answers the
     * moment one lands, not when the timer runs out.
     */
    public static final int DEFAULT_MAX_WAIT_MILLIS = 5_000;

    private final Connection connection;
    private final String topic;
    private final int partition;

    private long position;
    private long highWatermark;
    private int maxBytes = DEFAULT_MAX_BYTES;
    private int maxWaitMillis = DEFAULT_MAX_WAIT_MILLIS;
    private int minBytes;

    Consumer(Connection connection, String topic, int partition, long start) {
        this.connection = connection;
        this.topic = topic;
        this.partition = partition;
        this.position = start;
    }

    /** The offset of the next record this consumer will read. This is the number to persist. */
    public long position() {
        return position;
    }

    /** Moves the read position. Anything fetched and not yet returned is simply forgotten. */
    public void seek(long offset) {
        this.position = offset;
    }

    /**
     * Where the log ended at the last successful poll, and zero before the first one. A snapshot
     * rather than a live number: by the time it is read, the log may have grown.
     */
    public long highWatermark() {
        return highWatermark;
    }

    /** How many records this consumer was behind at the last poll. Same snapshot caveat. */
    public long lag() {
        return Math.max(0, highWatermark - position);
    }

    /**
     * How many bytes a response may carry. Larger means fewer round trips and a bigger buffer per
     * fetch; smaller risks {@link RecordExceedsMaxBytesException} on a large record.
     */
    public Consumer maxBytes(int bytes) {
        this.maxBytes = bytes;
        return this;
    }

    /**
     * How long the broker may hold a fetch that has nothing to answer.
     *
     * <p>The socket's own read timeout has to be larger, or the client gives up on a request the
     * broker is still legitimately holding — {@link Connection#open(String, int)} takes that
     * timeout, and it defaults to thirty seconds against this five.
     */
    public Consumer maxWaitMillis(int millis) {
        this.maxWaitMillis = millis;
        return this;
    }

    /** Holds a response until this much has accumulated, trading latency for round trips. */
    public Consumer minBytes(int bytes) {
        this.minBytes = bytes;
        return this;
    }

    /**
     * Reads the next records and advances {@link #position()} past them.
     *
     * <p><b>An empty list is not the end of anything.</b> A consumer that has caught up polls at the
     * high watermark and is answered with no records, which is the steady state of every consumer
     * keeping up; treating it as the end of the log is how a consumer stops for ever without
     * erroring.
     *
     * <p>The position advances past whole records only. A response can stop inside a record, because
     * {@code maxBytes} cuts on a byte boundary; the partial tail is dropped and the next poll asks
     * for that record again from its start. The broker will not do it for us — finding the record
     * boundary means parsing the batch, which is the work the zero-copy read path exists to avoid.
     *
     * @throws RecordExceedsMaxBytesException nothing whole came back and something partial did, so
     *     the next record is larger than this consumer is willing to receive and retrying is what a
     *     stall looks like from the inside
     */
    public List<byte[]> poll() {
        Fetched answer = connection.fetch(topic, partition, position, maxBytes, maxWaitMillis, minBytes);

        if (answer.records().isEmpty() && answer.truncated()) {
            throw new RecordExceedsMaxBytesException(position, answer.truncatedRecordBytes(), maxBytes);
        }

        highWatermark = answer.highWatermark();
        position += answer.records().size();
        return answer.records();
    }

    /**
     * {@link #poll()} one record at a time, fetching again whenever the last batch runs out.
     *
     * <p>{@link Iterator#hasNext()} is always true and blocks until there is something to return, so
     * a {@code for} loop over this never finishes on its own. That is the partition, not the
     * iterator: {@code hasNext() == false} would mean the log has ended, and it does not.
     *
     * <p>The position advances a whole fetch at a time, not a record at a time. Breaking out
     * mid-batch and persisting {@link #position()} skips the rest of that batch, so persist after
     * the loop, or count what was handled.
     */
    @Override
    public Iterator<byte[]> iterator() {
        return new Iterator<>() {

            private List<byte[]> batch = List.of();
            private int index;

            @Override
            public boolean hasNext() {
                while (index == batch.size()) {
                    batch = poll();
                    index = 0;
                }
                return true;
            }

            @Override
            public byte[] next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return batch.get(index++);
            }
        };
    }
}
