package ru.workinprogress.booblik.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProducerTest {

    private static ProducerConfig config(long lingerMillis, int maxBatchSize, AckPolicy ack) {
        return new ProducerConfig(maxBatchSize, lingerMillis, ack);
    }

    /**
     * The regression test the coroutine client needed twice, ported before it was needed here.
     *
     * <p>Records arrive one per window, so each is delivered by the timer rather than by a full
     * batch — the interleaving where that accumulator lost a record, its timeout having cancelled
     * the pending receive and the cancelled receive having swallowed it. {@code poll} with a
     * timeout cannot do that: it either returns an item or null.
     */
    @Test
    void noRecordsAreLostAcrossLingerWindows() throws Exception {
        long linger = 1;
        int rounds = 300;

        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address());
                Producer producer = new Producer(connection, config(linger, 100, AckPolicy.WRITTEN))) {

            List<CompletableFuture<Long>> pending = new ArrayList<>(rounds);
            for (int index = 0; index < rounds; index++) {
                pending.add(producer.send("orders", 0, bytes("r-" + index)));
                // Sleeping the window, not less: without this the records pile into full batches and
                // the interleaving this test is named after never happens.
                Thread.sleep(linger);
            }

            for (int index = 0; index < rounds; index++) {
                assertEquals(index, pending.get(index).get(30, TimeUnit.SECONDS), "record " + index);
            }
            assertEquals(rounds, broker.recordsIn("orders", 0).size());

            // And the interleaving actually happened. The bound is derived rather than chosen:
            // a batch-driven run would take exactly rounds/maxBatchSize requests; anything above that is the timer. A larger threshold measures the host's timer granularity instead — it passed on macOS and failed on Linux at 10 requests.
            assertTrue(
                    broker.requests() > rounds / 100,
                    rounds + " records went out in " + broker.requests() + " requests, so the window never fired");
        }
    }

    /** An hour of linger is still pending; only the batch being full can complete this. */
    @Test
    void aFullBatchDoesNotWaitForTheWindow() throws Exception {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address());
                Producer producer = new Producer(connection, config(3_600_000, 10, AckPolicy.WRITTEN))) {

            CompletableFuture<Long> last = null;
            for (int index = 0; index < 10; index++) {
                last = producer.send("orders", 0, bytes("r-" + index));
            }
            assertEquals(9L, last.get(10, TimeUnit.SECONDS));
            assertEquals(1, broker.requests());
        }
    }

    /** A request addresses one partition — a partition being what has one writer. */
    @Test
    void partitionsAccumulateSeparately() throws Exception {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address());
                Producer producer = new Producer(connection, config(3_600_000, 100, AckPolicy.WRITTEN))) {

            for (int partition = 0; partition < 3; partition++) {
                producer.send("orders", partition, bytes("x"));
            }
            producer.flush();

            for (int partition = 0; partition < 3; partition++) {
                assertEquals(1, broker.recordsIn("orders", partition).size(), "partition " + partition);
            }
            assertEquals(3, broker.requests());
        }
    }

    /** Dropping queued records would make every clean shutdown a silent data loss. */
    @Test
    void closeFlushesWhatIsQueued() throws Exception {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            Producer producer = new Producer(connection, config(3_600_000, 100, AckPolicy.WRITTEN));
            CompletableFuture<Long> pending = producer.send("orders", 0, bytes("x"));
            producer.close();

            assertEquals(0L, pending.get(5, TimeUnit.SECONDS));
            assertEquals(1, broker.recordsIn("orders", 0).size());
            assertThrows(IllegalStateException.class, () -> producer.send("orders", 0, bytes("y")));
        }
    }

    /**
     * A record that reaches the mailbox after the loop has gone must fail, not wait for ever.
     *
     * <p>Deterministic on purpose. The real race — a {@code send} slipping past the closed check
     * while {@code close} runs — has a window too narrow for threads to hit reliably, and a
     * hammering version of this test in the Python client passed just as happily with the handling
     * removed, which makes it a test of nothing. The Go client had the same race with a worse
     * ending, a panic, and that is where it was actually found.
     */
    @Test
    void aRecordThatArrivesAfterCloseIsFailed() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            Producer producer = new Producer(connection, config(1, 100, AckPolicy.WRITTEN));
            producer.close();

            // Past the public check, straight into the queue the loop no longer reads.
            CompletableFuture<Long> late = new CompletableFuture<>();
            producer.enqueuePastTheCheck("orders", 0, bytes("x"), late);
            producer.failLeftovers();

            assertTrue(late.isCompletedExceptionally(), "a late record would wait for ever");
            assertThrows(ExecutionException.class, () -> late.get(5, TimeUnit.SECONDS));
        }
    }

    /** Nothing is ever going to complete this from the wire, so it has to say so rather than hang. */
    @Test
    void ackNoneCompletesWithAnUnknownOffset() throws Exception {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address());
                Producer producer = new Producer(connection, config(1, 100, AckPolicy.NONE))) {

            assertEquals(
                    Producer.OFFSET_UNKNOWN,
                    producer.send("orders", 0, bytes("x")).get(10, TimeUnit.SECONDS));
        }
    }

    /**
     * A batch that fails fails for all of its records. Completing some and abandoning the rest would
     * leave callers awaiting a future nothing will ever finish.
     */
    @Test
    void aBrokerFailureReachesEveryWaitingCaller() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address());
                Producer producer = new Producer(connection, config(1, 100, AckPolicy.WRITTEN))) {

            broker.refuse(Code.RECORD_TOO_LARGE);
            List<CompletableFuture<Long>> pending = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                pending.add(producer.send("orders", 0, bytes("x")));
            }
            for (CompletableFuture<Long> future : pending) {
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            }
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
