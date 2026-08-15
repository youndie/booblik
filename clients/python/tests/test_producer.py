import threading
import time
import unittest

from booblik import OFFSET_UNKNOWN, AckPolicy, Connection, Producer, ProducerConfig

from .fake_broker import FakeBroker


class ProducerTest(unittest.TestCase):
    def setUp(self):
        self.broker = FakeBroker(partitions=3)
        self.addCleanup(self.broker.close)
        self.connection = Connection.connect(self.broker.address)
        self.addCleanup(self.connection.close)

    def producer(self, linger: float, max_batch_size: int, ack=AckPolicy.WRITTEN) -> Producer:
        producer = Producer(
            self.connection,
            ProducerConfig(max_batch_size=max_batch_size, linger=linger, ack=ack),
        )
        self.addCleanup(producer.close)
        return producer

    def test_no_records_are_lost_across_linger_windows(self):
        """The regression test the JVM client needed twice, ported before it was needed here.

        Records arrive one per window, so every one is delivered by the timer rather than by a full
        batch — the interleaving where the JVM accumulator lost a record, its timeout having
        cancelled the pending receive and the cancelled receive having swallowed it. `queue.get`
        with a timeout cannot do that: it either returns an item or raises Empty.
        """
        linger = 0.001
        rounds = 300
        producer = self.producer(linger=linger, max_batch_size=100)

        futures = []
        for index in range(rounds):
            futures.append(producer.send("orders", 0, f"r-{index}".encode()))
            # Sleeping the window, not less. Without this the records simply pile into full batches
            # and the interleaving this test is named after never happens — which is how the first
            # version of it passed in seventeen milliseconds while checking nothing of the sort.
            time.sleep(linger)

        for index, future in enumerate(futures):
            self.assertEqual(index, future.result(timeout=30), f"record {index}")

        self.assertEqual(rounds, len(self.broker.records_in("orders", 0)))

        # And the interleaving actually happened. The bound is derived rather than chosen: a
        # batch-driven run would take exactly rounds/max_batch_size requests, and anything above
        # that is the timer. A larger threshold measures the host's timer granularity instead.
        self.assertGreater(
            self.broker.request_count(),
            rounds // 100,
            "the records went out in too few requests, so the window never fired",
        )

    def test_a_full_batch_does_not_wait_for_the_window(self):
        """An hour of linger is still pending; only the batch being full can complete this."""
        producer = self.producer(linger=3600, max_batch_size=10)

        futures = [producer.send("orders", 0, f"r-{index}".encode()) for index in range(10)]
        self.assertEqual(9, futures[-1].result(timeout=10))
        self.assertEqual(1, self.broker.request_count())

    def test_partitions_accumulate_separately(self):
        """A request addresses one partition — a partition being what has one writer."""
        producer = self.producer(linger=3600, max_batch_size=100)

        for partition in range(3):
            producer.send("orders", partition, b"x")
        producer.flush(timeout=10)

        for partition in range(3):
            self.assertEqual(1, len(self.broker.records_in("orders", partition)))
        self.assertEqual(3, self.broker.request_count())

    def test_close_flushes_what_is_queued(self):
        """Dropping queued records would make every clean shutdown a silent data loss."""
        producer = Producer(self.connection, ProducerConfig(linger=3600, max_batch_size=100))
        future = producer.send("orders", 0, b"x")
        producer.close()

        self.assertEqual(0, future.result(timeout=5))
        self.assertEqual(1, len(self.broker.records_in("orders", 0)))
        with self.assertRaises(RuntimeError):
            producer.send("orders", 0, b"y")

    def test_ack_none_completes_with_an_unknown_offset(self):
        """Nothing is ever going to complete this from the wire, so the future has to say so rather
        than hanging."""
        producer = self.producer(linger=0.001, max_batch_size=100, ack=AckPolicy.NONE)
        self.assertEqual(OFFSET_UNKNOWN, producer.send("orders", 0, b"x").result(timeout=10))

    def test_a_broker_failure_reaches_every_waiting_caller(self):
        """A batch that fails fails for all of its records. Completing some and abandoning the rest
        would leave callers waiting on a future nothing will ever touch."""
        from booblik.errors import Code

        self.broker.refuse_with = Code.RECORD_TOO_LARGE
        producer = self.producer(linger=0.001, max_batch_size=100)

        futures = [producer.send("orders", 0, b"x") for _ in range(5)]
        for future in futures:
            with self.assertRaises(Exception):
                future.result(timeout=10)

    def test_a_record_that_arrives_after_close_is_failed_not_left_hanging(self):
        """A record that reaches the mailbox after the loop has gone must fail, not wait for ever.

        Deliberately deterministic. The real race — a `send` slipping past the closed check while
        `close` runs — has a window too narrow for threads to hit reliably here: a hammering version
        of this test passed just as happily with the handling removed, which makes it a test of
        nothing. The Go client had the same race with a worse ending, a panic, and that is where it
        was actually found.
        """
        from concurrent.futures import Future

        from booblik.producer import _Append, _Flush

        producer = Producer(self.connection, ProducerConfig(linger=0.001, max_batch_size=100))
        producer.close()

        late = Future()
        producer._mailbox.put(_Append("orders", 0, b"x", late))
        flush = threading.Event()
        producer._mailbox.put(_Flush(flush))
        producer._fail_leftovers()

        self.assertTrue(late.done(), "a record left after close would wait for ever")
        with self.assertRaises(RuntimeError):
            late.result()
        self.assertTrue(flush.is_set(), "a flush left after close would block its caller for ever")


if __name__ == "__main__":
    unittest.main()
