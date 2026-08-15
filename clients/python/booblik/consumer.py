"""Reading one partition, forward, from wherever it is told to start.

The expensive half of a client, and none of what makes it expensive is visible in the protocol. A
publisher that gets something wrong is told so by the broker; a consumer that gets something wrong
returns plausible bytes, or returns nothing and calls it the end of the log.
"""

from __future__ import annotations

from typing import Iterator

from .errors import RecordExceedsMaxBytesError

#: 1 MiB: large enough that a fetch is worth its round trip, small enough that one response cannot
#: dominate a small process. Every client in this repository uses the same number.
DEFAULT_MAX_BYTES = 1 << 20

#: Five seconds. A caught-up consumer with no wait asks again immediately and gets nothing, which is
#: a busy loop dressed as a poll — measured at about two thousand pointless requests a second
#: (benchmarking, measurement 24). Waiting costs new records nothing: the broker answers the moment
#: one lands, not when the timer runs out.
DEFAULT_MAX_WAIT_MILLIS = 5_000


class Consumer:
    """Reads one partition of one topic.

    **The position lives here, not in the broker.** That is half the reason this project has no
    consumer groups, no coordinator and no committed-offset storage: an offset is a number the
    reader already knows, and asking a broker to remember it is what drags in cluster consensus. The
    cost is that a restarting consumer has to be told where to resume — :attr:`position` is the
    number to write down, and writing it down *after* the records are dealt with rather than before
    is what makes a restart re-deliver instead of skip.

    **Not safe for concurrent use.** Every :meth:`poll` advances the position, and the connection
    underneath matches responses to requests in the order they were sent. One consumer, one
    partition, one thread.
    """

    def __init__(
        self,
        connection,
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
        #: The offset of the next record this consumer will read. This is the number to persist.
        self.position = start
        #: Where the log ended at the last successful poll; 0 before the first one. A snapshot
        #: rather than a live number — by the time it is read, the log may have grown.
        self.high_watermark = 0
        self.max_bytes = max_bytes
        self.max_wait_millis = max_wait_millis
        self.min_bytes = min_bytes

    def seek(self, offset: int) -> None:
        """Moves the read position. Anything fetched and not yet returned is simply forgotten."""
        self.position = offset

    @property
    def lag(self) -> int:
        """How many records this consumer was behind at the last poll. Same snapshot caveat."""
        return max(0, self.high_watermark - self.position)

    def poll(self) -> list[bytes]:
        """Reads the next records and advances :attr:`position` past them.

        **An empty list is not the end of anything.** A consumer that has caught up polls at the
        high watermark and is answered with no records, which is the steady state of every consumer
        keeping up; treating it as the end of the log is how a consumer stops for ever without
        erroring.

        The position advances past whole records only. A response can stop inside a record, because
        ``max_bytes`` cuts on a byte boundary; the partial tail is dropped and the next poll asks
        for that record again from its start. The broker will not do this for us — finding the
        record boundary means parsing the batch, which is the work the zero-copy read path exists to
        avoid.

        Raises :class:`~booblik.errors.RecordExceedsMaxBytesError` when nothing whole came back and
        something partial did: the next record is larger than this consumer is willing to receive,
        so retrying is what a stall looks like from the inside.
        """
        answer = self._connection.fetch(
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

    def records(self) -> Iterator[bytes]:
        """:meth:`poll` as a generator, yielding records one at a time and fetching as it goes.

        ::

            for record in consumer.records():
                handle(record)

        **The loop does not end**, and that is the shape of the thing rather than an oversight: a
        partition has no end, only a place it has not been written to yet. ``break`` out of it, or
        stop the process; an exception from a fetch propagates out of the loop as it would from any
        other generator.

        A generator and not a callback, because this way the loop belongs to the caller: ``break``
        works, ``return`` works, and the exception handler is the caller's own. It is also lazy — no
        fetch happens until the first record is asked for.

        The position advances a whole fetch at a time, not a record at a time. Breaking out
        mid-batch and persisting :attr:`position` skips the rest of that batch, so persist after the
        loop, or count what was handled.
        """
        while True:
            yield from self.poll()

    def __iter__(self) -> Iterator[bytes]:
        """``for record in consumer`` — the same endless stream as :meth:`records`."""
        return self.records()
