"""The synchronous connection: a socket, and the codec from :mod:`booblik.wire`.

Synchronous and blocking, deliberately, and it stays that way now that :mod:`booblik.aio` exists.
A synchronous client can be driven from asynchronous code by putting it on a thread; an asynchronous
one cannot be used from synchronous code without an event loop. Most Python that publishes events is
a script, a task worker or a request handler, so this is the one to reach for unless the caller
already has a loop.
"""

from __future__ import annotations

import socket

from . import wire
from .consumer import Consumer
from .errors import ProtocolError
from .partition import partition_for
from .wire import AckPolicy, Fetched, PartitionInfo, ProduceResult

__all__ = ["AckPolicy", "Connection", "Consumer", "Fetched", "PartitionInfo", "ProduceResult", "Topic"]


class Connection:
    """One connection to a broker.

    **Not safe for concurrent use.** Requests and responses are matched by a correlation id in the
    order they were sent, so two threads sharing a Connection would read each other's answers. Use
    one per thread, or a Producer, which owns its own.
    """

    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._correlation = 0

    @classmethod
    def connect(cls, address: str, timeout: float = 30.0) -> "Connection":
        """``"host:port"`` rather than two arguments, which is the form every configuration uses."""
        host, _, port = address.rpartition(":")
        return cls(host, int(port), timeout)

    def close(self) -> None:
        self._socket.close()

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *_) -> None:
        self.close()

    # -- moving bytes ----------------------------------------------------------------------------

    def _next_correlation(self) -> int:
        self._correlation += 1
        return self._correlation

    def _exchange(self, request: bytes, correlation: int) -> bytes:
        self._socket.sendall(request)
        length = wire.frame_length(self._read_exactly(wire.LENGTH_PREFIX_BYTES))
        return wire.read_header(self._read_exactly(length), correlation)

    def _read_exactly(self, count: int) -> bytes:
        chunks, remaining = [], count
        while remaining:
            chunk = self._socket.recv(remaining)
            if not chunk:
                raise ProtocolError(f"broker closed the connection with {remaining} bytes to go")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    # -- requests --------------------------------------------------------------------------------

    def metadata(self, *topics: str) -> dict[str, list[PartitionInfo]]:
        """Which topics exist and where each partition currently starts and ends. No names asks for
        everything.

        A named topic that does not exist fails the whole request with UNKNOWN_TOPIC_OR_PARTITION
        rather than being left out of the answer — otherwise "no such topic" and "the topic is
        empty" would arrive looking identical.
        """
        correlation = self._next_correlation()
        body = self._exchange(wire.encode_metadata(correlation, topics), correlation)
        return wire.decode_metadata(body)

    def produce(
        self,
        topic: str,
        partition: int,
        records: list[bytes],
        ack: AckPolicy = AckPolicy.WRITTEN,
    ) -> ProduceResult | None:
        """Appends records to one partition as a single request.

        They land **contiguously** — one request is written by one call into that partition's
        writer, so the offsets run from ``base_offset`` with nothing interleaved. It is **not**
        atomic: a crash mid-write leaves whatever reached the disk, and recovery keeps the prefix
        that passes its checksums. There are no transactions in this broker.

        Returns ``None`` under :attr:`AckPolicy.NONE`, because no answer is coming.

        Nothing here validates record sizes. The broker refuses empty records and empty batches with
        CORRUPT_REQUEST, and duplicating that rule client-side would create a second place to
        disagree with it — the rule belongs to whatever has to store the result.
        """
        correlation = self._next_correlation()
        request = wire.encode_produce(correlation, topic, partition, records, ack)

        if ack == AckPolicy.NONE:
            self._socket.sendall(request)
            return None
        return wire.decode_produce(self._exchange(request, correlation))

    def fetch(
        self,
        topic: str,
        partition: int,
        offset: int,
        max_bytes: int = 1 << 20,
        max_wait_millis: int = 0,
        min_bytes: int = 0,
    ) -> wire.Fetched:
        """Reads records from one partition, checksum-verified.

        ``max_bytes`` bounds the response **in bytes, not in records**, so it can stop inside one;
        see :attr:`wire.Fetched.truncated`. ``max_wait_millis`` is how long the broker may hold a
        request that has nothing to answer with, and 0 — the default here, though not in
        :class:`~booblik.consumer.Consumer` — returns immediately.

        ``min_bytes`` greater than ``max_bytes`` is CORRUPT_REQUEST: a request that can never be
        satisfied. That is left to the broker rather than checked here, so there is one place that
        decides it instead of two that can disagree.

        **The socket timeout has to exceed the wait**, or the client gives up on a request the
        broker is still legitimately holding. Checked here, because ``socket.timeout`` on a healthy
        long fetch is a confusing way to learn it.
        """
        timeout = self._socket.gettimeout()
        if timeout is not None and max_wait_millis / 1000 >= timeout:
            raise ValueError(
                f"max_wait_millis={max_wait_millis} needs a socket timeout above "
                f"{max_wait_millis / 1000:g}s, and this connection's is {timeout:g}s"
            )

        correlation = self._next_correlation()
        request = wire.encode_fetch(
            correlation, topic, partition, offset, max_bytes, max_wait_millis, min_bytes
        )
        return wire.decode_fetch(self._exchange(request, correlation), offset)

    def consumer(self, topic: str, partition: int, start: int = 0, **options) -> Consumer:
        """A :class:`~booblik.consumer.Consumer` reading this partition from ``start``.

        Reading "from the beginning" means starting at the partition's ``log_start_offset`` from
        :meth:`metadata`, not at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has ever
        dropped a segment to retention. Reading "only what is new" means its ``high_watermark``.
        """
        return Consumer(self, topic, partition, start, **options)

    def topic(self, name: str) -> "Topic":
        """A handle with the topic's partitions taken from the broker rather than from an argument.

        Asking is the point. A partition count supplied by hand can disagree with the broker, and
        what that produces — records piling into the partitions that exist while others are never
        written to — reads as a data problem rather than the configuration mistake it is.
        """
        answer = self.metadata(name)
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
        """Where a record with this key goes.

        A ``None`` key takes the next partition round-robin, and that counter advances on every
        call — so asking and then sending is two turns of it and the records start skipping
        partitions. With a key there is no such thing, the answer being a pure function of the key.
        """
        if key is None:
            chosen = self.partitions[self._round_robin % len(self.partitions)]
            self._round_robin += 1
            return chosen
        return self.partitions[partition_for(key, len(self.partitions))]

    def send(
        self,
        record: bytes,
        key: bytes | None = None,
        ack: AckPolicy = AckPolicy.WRITTEN,
    ) -> ProduceResult | None:
        """Publishes one record, choosing its partition from ``key``.

        One record per request throws away the largest single performance factor there is: the
        broker's own measurements put batches of a hundred at 4 335 482 records/s against 80 592 one
        at a time. Use :meth:`Connection.produce` with a list, or a Producer, whenever records are
        available together.
        """
        return self.connection.produce(self.name, self.partition_for(key), [record], ack)
