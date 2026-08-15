import asyncio
import unittest

from booblik import Code
from booblik.aio import OFFSET_UNKNOWN, AckPolicy, Connection, Producer, ProducerConfig
from booblik.partition import partition_for

from .fake_broker import FakeBroker

ALL_BYTES = bytes(range(256))


class AsyncConnectionTest(unittest.IsolatedAsyncioTestCase):
    """The asyncio client against the same fake broker the synchronous one uses.

    The same fixture on purpose: it is a real socket, so it does not care which side is waiting, and
    a second copy of it would be a second reading of the protocol to keep in step.
    """

    async def asyncSetUp(self):
        self.broker = FakeBroker(partitions=3)
        self.addCleanup(self.broker.close)
        self.connection = await Connection.connect(self.broker.address)
        self.addAsyncCleanup(self.connection.close)

    async def test_a_record_arrives_byte_for_byte(self):
        # Every byte value, because an encoding that damages the payload usually damages the high
        # half of it and leaves ASCII intact.
        records = [ALL_BYTES, b"second", b"\x00"]
        result = await self.connection.produce("orders", 2, records)

        self.assertEqual(0, result.base_offset)
        self.assertEqual(3, result.log_end_offset)
        self.assertEqual(records, self.broker.records_in("orders", 2))

    async def test_ack_none_does_not_wait_for_an_answer(self):
        """The broker sends nothing at all, and a client that reads a response here waits for ever.
        The bound is what makes this a test rather than a hang."""
        result = await asyncio.wait_for(
            self.connection.produce("orders", 0, [b"x"], AckPolicy.NONE), timeout=5
        )
        self.assertIsNone(result, "AckPolicy.NONE has no offset to report")

    async def test_a_refusal_is_an_error_and_keeps_the_connection(self):
        from booblik.errors import BrokerError

        self.broker.refuse_with = Code.UNKNOWN_TOPIC_OR_PARTITION
        with self.assertRaises(BrokerError) as caught:
            await self.connection.produce("nope", 0, [b"x"])
        self.assertEqual(Code.UNKNOWN_TOPIC_OR_PARTITION, caught.exception.code)

        # Framing was intact, so the connection is still usable — only a frame length out of range
        # closes one. A client that tore the socket down here would turn a refusal into an outage.
        self.broker.refuse_with = Code.NONE
        self.assertIsNotNone(await self.connection.produce("orders", 0, [b"x"]))

    async def test_metadata_and_key_routing(self):
        topic = await self.connection.topic("orders")
        self.assertEqual(3, len(topic.partitions))

        key = b"user-1"
        self.assertEqual(topic.partitions[partition_for(key, 3)], topic.partition_for(key))
        # The same key, every time: this is what makes asking-then-sending safe with a key.
        self.assertEqual(topic.partition_for(key), topic.partition_for(key))

    async def test_unkeyed_routing_advances_round_robin(self):
        topic = await self.connection.topic("orders")
        seen = [topic.partition_for(None) for _ in range(9)]
        self.assertEqual({0: 3, 1: 3, 2: 3}, {p: seen.count(p) for p in set(seen)})


class AsyncProducerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.broker = FakeBroker(partitions=3)
        self.addCleanup(self.broker.close)
        self.connection = await Connection.connect(self.broker.address)
        self.addAsyncCleanup(self.connection.close)

    def producer(self, linger: float, max_batch_size: int, ack=AckPolicy.WRITTEN) -> Producer:
        producer = Producer(
            self.connection,
            ProducerConfig(max_batch_size=max_batch_size, linger=linger, ack=ack),
        )
        self.addAsyncCleanup(producer.close)
        return producer

    async def test_no_records_are_lost_across_linger_windows(self):
        """Records arrive one per window, so every one is delivered by the timer rather than by a
        full batch — the interleaving where the JVM accumulator lost a record twice.

        Worth knowing what this does **not** demonstrate: the loop keeps its pending getter instead
        of using `asyncio.wait_for(queue.get(), timeout)`, and swapping in that spelling passed this
        test five times over. `asyncio.Queue.get` handles its own cancellation, so the hazard the
        shape guards against is closed in CPython. The shape stays because `wait_for` discards a
        result that arrives as cancellation lands, and this loop is the one place such a loss would
        be silent — but that is insurance, not a reproduced bug, and saying otherwise would make
        this comment the kind of confident story the project keeps catching.
        """
        linger = 0.001
        rounds = 300
        producer = self.producer(linger=linger, max_batch_size=100)

        futures = []
        for index in range(rounds):
            futures.append(await producer.send("orders", 0, f"r-{index}".encode()))
            # Sleeping the window, not less: without this the records pile into full batches and the
            # interleaving this test is named after never happens.
            await asyncio.sleep(linger)

        for index, future in enumerate(futures):
            self.assertEqual(index, await asyncio.wait_for(future, timeout=30), f"record {index}")

        self.assertEqual(rounds, len(self.broker.records_in("orders", 0)))

        # And the interleaving actually happened. The bound is derived rather than chosen: a
        # batch-driven run would take exactly rounds/max_batch_size requests, and anything above
        # that is the timer.
        self.assertGreater(self.broker.request_count(), rounds // 100)

    async def test_a_full_batch_does_not_wait_for_the_window(self):
        """An hour of linger is still pending; only the batch being full can complete this."""
        producer = self.producer(linger=3600, max_batch_size=10)

        futures = [await producer.send("orders", 0, f"r-{i}".encode()) for i in range(10)]
        self.assertEqual(9, await asyncio.wait_for(futures[-1], timeout=10))
        self.assertEqual(1, self.broker.request_count())

    async def test_partitions_accumulate_separately(self):
        """A request addresses one partition — a partition being what has one writer."""
        producer = self.producer(linger=3600, max_batch_size=100)

        for partition in range(3):
            await producer.send("orders", partition, b"x")
        await asyncio.wait_for(producer.flush(), timeout=10)

        for partition in range(3):
            self.assertEqual(1, len(self.broker.records_in("orders", partition)))
        self.assertEqual(3, self.broker.request_count())

    async def test_close_flushes_what_is_queued(self):
        """Dropping queued records would make every clean shutdown a silent data loss."""
        producer = Producer(self.connection, ProducerConfig(linger=3600, max_batch_size=100))
        future = await producer.send("orders", 0, b"x")
        await producer.close()

        self.assertEqual(0, await asyncio.wait_for(future, timeout=5))
        self.assertEqual(1, len(self.broker.records_in("orders", 0)))
        with self.assertRaises(RuntimeError):
            await producer.send("orders", 0, b"y")

    async def test_ack_none_completes_with_an_unknown_offset(self):
        producer = self.producer(linger=0.001, max_batch_size=100, ack=AckPolicy.NONE)
        future = await producer.send("orders", 0, b"x")
        self.assertEqual(OFFSET_UNKNOWN, await asyncio.wait_for(future, timeout=10))

    async def test_a_broker_failure_reaches_every_waiting_caller(self):
        """A batch that fails fails for all of its records."""
        self.broker.refuse_with = Code.RECORD_TOO_LARGE
        producer = self.producer(linger=0.001, max_batch_size=100)

        futures = [await producer.send("orders", 0, b"x") for _ in range(5)]
        for future in futures:
            with self.assertRaises(Exception):
                await asyncio.wait_for(future, timeout=10)

    async def test_a_record_that_arrives_after_close_is_failed(self):
        """Deterministic, for the same reason as in the synchronous client: the real race has a
        window too narrow for a hammering test to hit reliably, and one that cannot fail is not a
        test. The Go client had this race with a worse ending, a panic, and that is where it was
        actually found."""
        from booblik.aio import _Append

        producer = Producer(self.connection, ProducerConfig(linger=0.001, max_batch_size=100))
        await producer.close()

        late = asyncio.get_running_loop().create_future()
        producer._mailbox.put_nowait(_Append("orders", 0, b"x", late))
        producer._fail_leftovers()

        self.assertTrue(late.done(), "a record left after close would wait for ever")
        with self.assertRaises(RuntimeError):
            late.result()


if __name__ == "__main__":
    unittest.main()


class AsyncConsumerTest(unittest.IsolatedAsyncioTestCase):
    """The reading half of the asyncio client.

    Thinner than tests/test_consumer.py deliberately: the decoder, the checksum and the truncated
    tail live in booblik.wire and are the *same code* both clients call, so checking them twice
    would check one implementation twice. What is asyncio's own — the position, the async generator,
    the stall — is checked here.
    """

    async def asyncSetUp(self):
        self.broker = FakeBroker(partitions=1)
        self.addCleanup(self.broker.close)
        self.connection = await Connection.connect(self.broker.address)
        self.addAsyncCleanup(self.connection.close)

    def consumer(self, **options):
        options.setdefault("max_wait_millis", 0)
        return self.connection.consumer("t", 0, 0, **options)

    async def test_records_come_back_in_order(self):
        payloads = [ALL_BYTES, b"\x00", b"third"]
        self.broker.seed("t", 0, *payloads)

        consumer = self.consumer()
        self.assertEqual(payloads, await consumer.poll())
        self.assertEqual(3, consumer.position)
        self.assertEqual(3, consumer.high_watermark)

    async def test_the_high_watermark_is_empty_and_not_an_error(self):
        self.broker.seed("t", 0, b"one")
        consumer = self.consumer()
        await consumer.poll()

        self.assertEqual([], await consumer.poll())
        self.assertEqual(1, consumer.position)

    async def test_truncated_tail_is_dropped_and_refetched(self):
        self.broker.seed("t", 0, b"A" * 100, b"B" * 100)
        consumer = self.consumer(max_bytes=150)

        self.assertEqual([b"A" * 100], await consumer.poll())
        self.assertEqual(1, consumer.position, "the partial record must not be counted")
        self.assertEqual([b"B" * 100], await consumer.poll())

    async def test_record_larger_than_max_bytes_is_reported(self):
        from booblik.errors import RecordExceedsMaxBytesError

        self.broker.seed("t", 0, b"A" * 500)
        consumer = self.consumer(max_bytes=100)

        with self.assertRaises(RecordExceedsMaxBytesError) as caught:
            await consumer.poll()
        self.assertEqual(500, caught.exception.record_bytes)
        self.assertEqual(0, consumer.position)

    async def test_corrupt_record_is_rejected(self):
        from booblik.errors import CorruptRecordError

        self.broker.seed("t", 0, b"payload")
        self.broker.corrupt = True

        with self.assertRaises(CorruptRecordError):
            await self.consumer().poll()

    async def test_async_iteration_yields_every_record_once(self):
        seeded = [b"a", b"b", b"c"]
        self.broker.seed("t", 0, *seeded)

        read = []
        async for record in self.consumer():
            read.append(record)
            if len(read) == len(seeded):
                break
        self.assertEqual(seeded, read)

    async def test_cancelling_the_task_stops_the_stream(self):
        """The endless loop is stopped by cancelling the task waiting on it, and the cancellation
        arrives as CancelledError out of the await inside — no unregistering, no callback to unwind.
        The bound is what makes this a test rather than a hang."""
        consumer = self.consumer(max_wait_millis=0)

        async def read_for_ever():
            async for _ in consumer:
                await asyncio.sleep(0)

        task = asyncio.create_task(read_for_ever())
        await asyncio.sleep(0.05)
        task.cancel()

        with self.assertRaises(asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=5)
