"""An asyncio publisher.

A separate thing from :mod:`booblik.connection`, not a rewrite of it: a synchronous client can be
driven from asynchronous code on a thread, and an asynchronous one cannot be used from synchronous
code at all. Both share :mod:`booblik.wire` — the codec is bytes in, bytes out, and has nothing to
say about who is waiting.

Still no dependencies: ``asyncio`` is the standard library.

    async with await booblik.aio.Connection.connect("localhost:9092") as connection:
        topic = await connection.topic("orders")
        await topic.send(payload, key=key)
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field

from . import wire
from .consumer import DEFAULT_MAX_BYTES, DEFAULT_MAX_WAIT_MILLIS
from .errors import ProtocolError, RecordExceedsMaxBytesError
from .partition import partition_for
from .wire import AckPolicy, Fetched, PartitionInfo, ProduceResult

__all__ = [
    "AckPolicy",
    "Connection",
    "Consumer",
    "Fetched",
    "PartitionInfo",
    "ProduceResult",
    "Producer",
    "ProducerConfig",
    "Topic",
]

#: What a future carries when the batch went out under :attr:`AckPolicy.NONE`. The record was sent;
#: no offset exists, because none is assigned until the writer reaches the batch.
OFFSET_UNKNOWN = -1


class Connection:
    """One connection to a broker.

    **Not safe for concurrent use.** Requests and responses are matched by a correlation id in the
    order they were sent, so two tasks sharing a Connection would read each other's answers. Use one
    per task, or a :class:`Producer`, which owns its own.
    """

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self._reader = reader
        self._writer = writer
        self._correlation = 0

    @classmethod
    async def connect(cls, address: str, timeout: float = 30.0) -> "Connection":
        """``"host:port"`` rather than two arguments, which is the form every configuration uses."""
        host, _, port = address.rpartition(":")
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host, int(port)), timeout=timeout
        )
        return cls(reader, writer)

    async def close(self) -> None:
        self._writer.close()
        try:
            await self._writer.wait_closed()
        except OSError:
            # A connection the broker already dropped is closed either way, and a caller that said
            # it is finished has nothing to do with the news.
            pass

    async def __aenter__(self) -> "Connection":
        return self

    async def __aexit__(self, *_) -> None:
        await self.close()

    # -- moving bytes ----------------------------------------------------------------------------

    def _next_correlation(self) -> int:
        self._correlation += 1
        return self._correlation

    async def _exchange(self, request: bytes, correlation: int) -> bytes:
        self._writer.write(request)
        await self._writer.drain()

        try:
            prefix = await self._reader.readexactly(wire.LENGTH_PREFIX_BYTES)
            body = await self._reader.readexactly(wire.frame_length(prefix))
        except asyncio.IncompleteReadError as failure:
            raise ProtocolError("broker closed the connection mid-frame") from failure
        return wire.read_header(body, correlation)

    # -- requests --------------------------------------------------------------------------------

    async def metadata(self, *topics: str) -> dict[str, list[PartitionInfo]]:
        """Which topics exist and where each partition currently starts and ends."""
        correlation = self._next_correlation()
        body = await self._exchange(wire.encode_metadata(correlation, topics), correlation)
        return wire.decode_metadata(body)

    async def produce(
        self,
        topic: str,
        partition: int,
        records: list[bytes],
        ack: AckPolicy = AckPolicy.WRITTEN,
    ) -> ProduceResult | None:
        """Appends records to one partition as a single request.

        Returns ``None`` under :attr:`AckPolicy.NONE`, because no answer is coming — not an empty
        response, nothing, since no offset exists until the writer reaches the batch.
        """
        correlation = self._next_correlation()
        request = wire.encode_produce(correlation, topic, partition, records, ack)

        if ack == AckPolicy.NONE:
            self._writer.write(request)
            await self._writer.drain()
            return None
        return wire.decode_produce(await self._exchange(request, correlation))

    async def fetch(
        self,
        topic: str,
        partition: int,
        offset: int,
        max_bytes: int = 1 << 20,
        max_wait_millis: int = 0,
        min_bytes: int = 0,
    ) -> wire.Fetched:
        """Reads records from one partition, checksum-verified.

        ``max_bytes`` bounds the response **in bytes, not in records**, so it can stop inside one.

        **The wait needs no timeout arithmetic here**, which is the one place this client has less
        to worry about than the synchronous one: a stream reader has no per-operation deadline of
        its own, so a long fetch simply waits, and a caller who wants to give up wraps the call in
        :func:`asyncio.timeout`. The synchronous client has to compare the wait against the socket
        timeout, because there the two deadlines are the same object.
        """
        correlation = self._next_correlation()
        request = wire.encode_fetch(
            correlation, topic, partition, offset, max_bytes, max_wait_millis, min_bytes
        )
        return wire.decode_fetch(await self._exchange(request, correlation), offset)

    def consumer(self, topic: str, partition: int, start: int = 0, **options) -> "Consumer":
        """A :class:`Consumer` reading this partition from ``start``.

        Reading "from the beginning" means starting at the partition's ``log_start_offset`` from
        :meth:`metadata`, not at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has ever
        dropped a segment to retention. Reading "only what is new" means its ``high_watermark``.
        """
        return Consumer(self, topic, partition, start, **options)

    async def topic(self, name: str) -> "Topic":
        """A handle whose partitions come from the broker rather than from an argument."""
        answer = await self.metadata(name)
        partitions = [info.partition for info in answer.get(name, [])]
        if not partitions:
            raise ProtocolError(f"broker has no partitions for {name!r}")
        return Topic(self, name, partitions)


class Topic:
    """One topic, so its name and its routing stop being arguments to every call."""

    def __init__(self, connection: Connection, name: str, partitions: list[int]):
        self.connection = connection
        self.name = name
        self.partitions = partitions
        self._round_robin = 0

    def partition_for(self, key: bytes | None) -> int:
        """A ``None`` key takes the next partition round-robin, and that counter advances on every
        call — so asking and then sending is two turns of it. With a key there is no such thing."""
        if key is None:
            chosen = self.partitions[self._round_robin % len(self.partitions)]
            self._round_robin += 1
            return chosen
        return self.partitions[partition_for(key, len(self.partitions))]

    async def send(
        self,
        record: bytes,
        key: bytes | None = None,
        ack: AckPolicy = AckPolicy.WRITTEN,
    ) -> ProduceResult | None:
        return await self.connection.produce(self.name, self.partition_for(key), [record], ack)


class Consumer:
    """Reads one partition of one topic, forward.

    **The position lives here, not in the broker.** There are no consumer groups, no coordinator and
    no committed offsets: an offset is a number the reader already knows, and asking a broker to
    remember it is what drags in cluster consensus. :attr:`position` is the number to persist, and
    persisting it *after* the records are dealt with rather than before is what makes a restart
    re-deliver instead of skip.

    **Not safe for concurrent use.** Every :meth:`poll` advances the position, and the connection
    matches responses to requests in the order they were sent. One consumer, one partition, one task.
    """

    def __init__(
        self,
        connection: Connection,
        topic: str,
        partition: int,
        start: int = 0,
        max_bytes: int = DEFAULT_MAX_BYTES,
        max_wait_millis: int = DEFAULT_MAX_WAIT_MILLIS,
        min_bytes: int = 0,
    ):
        self._connection = connection
        self.topic = topic
        self.partition = partition
        self.position = start
        self.high_watermark = 0
        self.max_bytes = max_bytes
        self.max_wait_millis = max_wait_millis
        self.min_bytes = min_bytes

    def seek(self, offset: int) -> None:
        """Moves the read position. Anything fetched and not yet returned is simply forgotten."""
        self.position = offset

    @property
    def lag(self) -> int:
        """How many records this consumer was behind at the last poll — a snapshot, not a live
        number: by the time it is read, the log may have grown."""
        return max(0, self.high_watermark - self.position)

    async def poll(self) -> list[bytes]:
        """Reads the next records and advances :attr:`position` past them.

        **An empty list is not the end of anything** — it is what a consumer that has caught up
        gets, which is the steady state of every consumer keeping up.

        The position advances past whole records only: a response can stop inside a record, because
        ``max_bytes`` cuts on a byte boundary, and the partial tail is re-read from its start by the
        next poll. Raises :class:`~booblik.errors.RecordExceedsMaxBytesError` when nothing whole
        came back and something partial did — the next record is larger than this consumer is
        willing to receive, and retrying is what a stall looks like from the inside.
        """
        answer = await self._connection.fetch(
            self.topic,
            self.partition,
            self.position,
            self.max_bytes,
            self.max_wait_millis,
            self.min_bytes,
        )

        if not answer.records and answer.truncated:
            raise RecordExceedsMaxBytesError(
                self.position, answer.truncated_record_bytes, self.max_bytes
            )

        self.high_watermark = answer.high_watermark
        self.position += len(answer.records)
        return answer.records

    async def records(self):
        """:meth:`poll` as an async generator, yielding records one at a time::

            async for record in consumer.records():
                await handle(record)

        **The loop does not end**: a partition has no end, only a place it has not been written to
        yet. ``break`` out of it, or cancel the task — a cancellation propagates out of the ``await``
        inside as :class:`asyncio.CancelledError`, which is what cancelling is supposed to look like
        and is why there is no callback registration to unwind.

        An async generator and not a queue the consumer pushes into: a queue would read ahead of
        what the caller has handled, which moves the position past records nobody has processed, and
        it would have nowhere to put the error that ended it.
        """
        while True:
            for record in await self.poll():
                yield record

    def __aiter__(self):
        """``async for record in consumer`` — the same endless stream as :meth:`records`."""
        return self.records()


@dataclass
class ProducerConfig:
    max_batch_size: int = 100
    #: How long an incomplete batch waits for company, in seconds. Zero is not the fast setting —
    #: it sends every record on its own, which the broker's own measurements put at 80 592
    #: records/s against 4 335 482 for batches of a hundred.
    linger: float = 0.005
    ack: AckPolicy = AckPolicy.WRITTEN


@dataclass
class _Append:
    topic: str
    partition: int
    record: bytes
    future: asyncio.Future


@dataclass
class _Flush:
    done: asyncio.Future


@dataclass
class _Batch:
    records: list[bytes] = field(default_factory=list)
    futures: list[asyncio.Future] = field(default_factory=list)


_CLOSE = object()


class Producer:
    """Accumulates records and sends them in batches.

    **It owns its Connection**: one task holds the pending records and is the only writer to that
    socket. Do not use the same Connection directly while a Producer has it — responses are matched
    in order, and a second writer takes somebody else's answer.
    """

    def __init__(self, connection: Connection, config: ProducerConfig | None = None):
        self._connection = connection
        self._config = config or ProducerConfig()
        self._mailbox: asyncio.Queue = asyncio.Queue()
        self._closed = False
        self._loop_task = asyncio.ensure_future(self._run())

    async def send(self, topic: str, partition: int, record: bytes) -> asyncio.Future:
        """Queues a record and returns where its offset will arrive."""
        if self._closed:
            raise RuntimeError("producer is closed")
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        await self._mailbox.put(_Append(topic, partition, record, future))
        return future

    async def flush(self) -> None:
        """Sends everything queued and waits for the broker to answer all of it."""
        done: asyncio.Future = asyncio.get_running_loop().create_future()
        await self._mailbox.put(_Flush(done))
        await done

    async def close(self) -> None:
        """Flushes what is queued and stops the accumulator. Does not close the Connection."""
        if self._closed:
            return
        self._closed = True
        await self._mailbox.put(_CLOSE)
        await self._loop_task
        self._fail_leftovers()

    async def __aenter__(self) -> "Producer":
        return self

    async def __aexit__(self, *_) -> None:
        await self.close()

    def _fail_leftovers(self) -> None:
        """Fails anything that reached the mailbox after the loop had already returned.

        A ``send`` that passed the closed check just before ``close`` ran can queue its record after
        the loop is gone, and it would then await a future nothing will ever complete. The Go client
        had this race with a worse ending — it closed its channel, so the late send panicked — and
        that is how it was noticed at all.
        """
        while True:
            try:
                leftover = self._mailbox.get_nowait()
            except asyncio.QueueEmpty:
                return
            if isinstance(leftover, _Append) and not leftover.future.done():
                leftover.future.set_exception(RuntimeError("producer is closed"))
            elif isinstance(leftover, _Flush) and not leftover.done.done():
                leftover.done.set_result(None)

    async def _run(self) -> None:
        pending: dict[tuple[str, int], _Batch] = {}
        deadline: float | None = None
        loop = asyncio.get_running_loop()

        # The pending getter is kept **across iterations** and never cancelled.
        #
        # The obvious spelling is `await asyncio.wait_for(self._mailbox.get(), timeout)`, and the
        # reason to avoid it is narrower than it first looks — measured rather than assumed. That
        # spelling was tried here and **did not** lose records over three hundred rounds, five times
        # over: `asyncio.Queue.get` handles its own cancellation, putting nothing on the floor and
        # waking the next getter if an item is waiting. The historical hazard is closed in CPython.
        #
        # What remains true of `wait_for` in general is that a result arriving exactly as the
        # cancellation lands is discarded, and that this loop is the one place where such a loss
        # would be silent — the caller would await an offset for ever while everybody else is
        # served, which is what the JVM client did twice. `asyncio.wait` never touches the task, so
        # the question does not arise, and it costs one variable. Cheap insurance against a
        # guarantee that belongs to somebody else's implementation.
        getter: asyncio.Task | None = None

        try:
            while True:
                if getter is None:
                    getter = asyncio.ensure_future(self._mailbox.get())

                timeout = None if deadline is None else max(0.0, deadline - loop.time())
                done, _ = await asyncio.wait({getter}, timeout=timeout)

                if not done:
                    await self._deliver(pending)
                    deadline = None
                    continue

                command = getter.result()
                getter = None

                if command is _CLOSE:
                    await self._deliver(pending)
                    return

                if isinstance(command, _Flush):
                    await self._deliver(pending)
                    deadline = None
                    if not command.done.done():
                        command.done.set_result(None)
                    continue

                batch = pending.setdefault((command.topic, command.partition), _Batch())
                batch.records.append(command.record)
                batch.futures.append(command.future)

                # The window is measured from the **first** record of the batch and never
                # restarted. Timing from the last would let a steady trickle postpone the send
                # indefinitely, turning a latency bound into a latency hope.
                if deadline is None:
                    deadline = loop.time() + self._config.linger
                if len(batch.records) >= self._config.max_batch_size:
                    await self._deliver(pending)
                    deadline = None
        finally:
            if getter is not None and not getter.done():
                getter.cancel()

    async def _deliver(self, pending: dict[tuple[str, int], _Batch]) -> None:
        for (topic, partition), batch in list(pending.items()):
            del pending[(topic, partition)]

            try:
                result = await self._connection.produce(
                    topic, partition, batch.records, self._config.ack
                )
            except Exception as failure:  # noqa: BLE001 — every waiting caller has to learn of it
                for future in batch.futures:
                    if not future.done():
                        future.set_exception(failure)
                continue

            for index, future in enumerate(batch.futures):
                if future.done():
                    continue
                if result is None:
                    future.set_result(OFFSET_UNKNOWN)
                else:
                    # One request is written by one call, so the records are contiguous.
                    future.set_result(result.base_offset + index)
