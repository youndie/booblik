"""The accumulator.

One record per request throws away the largest single performance factor in this project: the
broker's own measurements put batches of a hundred at 4 335 482 records/s against 80 592 one at a
time. That is why batching is not an optimisation to enable later — it is what a producer is.
"""

from __future__ import annotations

import queue
import threading
import time
from concurrent.futures import Future
from dataclasses import dataclass, field

from .connection import AckPolicy, Connection

#: What a Future carries when the batch went out under :attr:`AckPolicy.NONE`. The record was sent;
#: no offset exists, because none is assigned until the writer reaches the batch.
OFFSET_UNKNOWN = -1


@dataclass
class ProducerConfig:
    #: Records per request. Reached first, the batch goes at once.
    max_batch_size: int = 100
    #: How long an incomplete batch waits for company, in seconds.
    #:
    #: Zero is not the fast setting — it sends every record on its own. Non-zero trades a bounded
    #: amount of latency for the factor above.
    linger: float = 0.005
    ack: AckPolicy = AckPolicy.WRITTEN
    #: Bounds one delivery, so a broker that stops answering fails the records waiting on it
    #: instead of hanging the accumulator for ever.
    request_timeout: float = 30.0


@dataclass
class _Append:
    topic: str
    partition: int
    record: bytes
    future: Future


@dataclass
class _Flush:
    done: threading.Event


@dataclass
class _Batch:
    records: list[bytes] = field(default_factory=list)
    futures: list[Future] = field(default_factory=list)


_CLOSE = object()


class Producer:
    """Accumulates records and sends them in batches.

    **It owns its Connection**: one thread holds the pending records and is the only writer to that
    socket. Do not use the same Connection directly while a Producer has it — responses are matched
    in order, and a second writer takes somebody else's answer.

    Records for different partitions accumulate separately and go out as separate requests, because
    a request addresses one partition — a partition being what has one writer.
    """

    def __init__(self, connection: Connection, config: ProducerConfig | None = None):
        self._connection = connection
        self._config = config or ProducerConfig()
        self._mailbox: queue.Queue = queue.Queue()
        self._closed = threading.Event()
        self._thread = threading.Thread(target=self._run, name="booblik-producer", daemon=True)
        self._thread.start()

    def send(self, topic: str, partition: int, record: bytes) -> Future:
        """Queues a record and returns where its offset will arrive.

        The record is not on the wire when this returns — that is the point. Call ``result()`` on
        the future to know it landed, or :meth:`flush` to push everything queued.
        """
        if self._closed.is_set():
            raise RuntimeError("producer is closed")
        future: Future = Future()
        self._mailbox.put(_Append(topic, partition, record, future))
        return future

    def flush(self, timeout: float | None = None) -> None:
        """Sends everything queued and waits for the broker to answer all of it."""
        done = threading.Event()
        self._mailbox.put(_Flush(done))
        if not done.wait(timeout):
            raise TimeoutError("flush timed out")

    def close(self) -> None:
        """Flushes what is queued and stops the accumulator. Does not close the Connection."""
        if self._closed.is_set():
            return
        self._closed.set()
        self._mailbox.put(_CLOSE)
        self._thread.join()
        self._fail_leftovers()

    def _fail_leftovers(self) -> None:
        """Fails anything that reached the mailbox after the loop had already returned.

        A `send` that passed the closed check just before `close` ran can put its record after the
        loop is gone, and it would then wait on a future nothing will ever complete. Failing those
        is the difference between a caller that learns something and a caller that stops.

        The Go client had the same race with a worse ending — it closed its mailbox, so the late
        send panicked outright, and that is how this was noticed at all. Here the window is narrow
        enough that threads do not reliably hit it, which is why the test calls this directly
        instead of pretending to reproduce a race it cannot.
        """
        while True:
            try:
                leftover = self._mailbox.get_nowait()
            except queue.Empty:
                return
            if isinstance(leftover, _Append):
                leftover.future.set_exception(RuntimeError("producer is closed"))
            elif isinstance(leftover, _Flush):
                leftover.done.set()

    def __enter__(self) -> "Producer":
        return self

    def __exit__(self, *_) -> None:
        self.close()

    def _run(self) -> None:
        pending: dict[tuple[str, int], _Batch] = {}
        # When the current window ends, or None when nothing is waiting.
        deadline: float | None = None

        while True:
            timeout = None if deadline is None else max(0.0, deadline - time.monotonic())
            try:
                # `get` with a timeout either returns an item or raises Empty. It cannot take an
                # item off the queue and then drop it, which is what the equivalent on the JVM did
                # twice — a timeout there cancels the pending receive, and a cancelled receive could
                # swallow a record, after which whoever awaited its offset waited for ever.
                command = self._mailbox.get(timeout=timeout)
            except queue.Empty:
                self._deliver(pending)
                deadline = None
                continue

            if command is _CLOSE:
                self._deliver(pending)
                return

            if isinstance(command, _Flush):
                self._deliver(pending)
                deadline = None
                command.done.set()
                continue

            batch = pending.setdefault((command.topic, command.partition), _Batch())
            batch.records.append(command.record)
            batch.futures.append(command.future)

            # The window is measured from the **first** record of the batch and never restarted.
            # Timing from the last would let a steady trickle postpone the send indefinitely,
            # turning a latency bound into a latency hope.
            if deadline is None:
                deadline = time.monotonic() + self._config.linger
            if len(batch.records) >= self._config.max_batch_size:
                self._deliver(pending)
                deadline = None

    def _deliver(self, pending: dict[tuple[str, int], _Batch]) -> None:
        for (topic, partition), batch in list(pending.items()):
            del pending[(topic, partition)]

            try:
                result = self._connection.produce(topic, partition, batch.records, self._config.ack)
            except Exception as failure:  # noqa: BLE001 — every waiting caller has to learn of it
                for future in batch.futures:
                    future.set_exception(failure)
                continue

            for index, future in enumerate(batch.futures):
                if result is None:
                    future.set_result(OFFSET_UNKNOWN)
                else:
                    # One request is written by one call, so the records are contiguous.
                    future.set_result(result.base_offset + index)
