"""Why the broker refused a request. Values are on the wire; see docs/api/protocol-wire.md §5."""

from enum import IntEnum


class Code(IntEnum):
    NONE = 0
    UNKNOWN_TOPIC_OR_PARTITION = 1
    OFFSET_OUT_OF_RANGE = 2
    RECORD_TOO_LARGE = 3
    UNSUPPORTED_VERSION = 4
    CORRUPT_REQUEST = 5


class BrokerError(Exception):
    """A refusal from the broker, as opposed to anything going wrong with the connection.

    A refusal is a result and not a transport failure: the connection stays usable, because framing
    was intact — the broker understood the request and declined it. Only a frame length outside the
    allowed range closes a connection.
    """

    def __init__(self, code: Code):
        self.code = code
        super().__init__(f"broker refused the request: {code.name}")


class ProtocolError(Exception):
    """The bytes on the connection do not make sense — a length out of range, a short response, or
    an answer carrying somebody else's correlation id."""


class CorruptRecordError(Exception):
    """A record whose bytes do not match the checksum stored with them.

    The client is the only party that can notice. On the zero-copy read path the broker sends
    segment bytes to the socket without looking at them — that is what zero-copy means — so the sum
    is computed once at write time to protect the **disk**, and verified once at read time, by
    whoever finally holds the bytes. A client that skips it silently switches off the project's only
    defence against a corrupted log.
    """

    def __init__(self, offset: int, stored: int, computed: int):
        self.offset = offset
        self.stored = stored
        self.computed = computed
        super().__init__(
            f"record at offset {offset} fails its checksum: "
            f"stored {stored:#010x}, computed {computed:#010x}"
        )


class RecordExceedsMaxBytesError(Exception):
    """The next record is larger than ``max_bytes``, so it can never arrive whole.

    One of the two ways a consumer stalls, and the one that does not resolve itself. A response
    with no whole records and a truncated tail means the record does not fit; a client that drops
    the tail and retries makes exactly the same request for ever — running, reporting nothing,
    never advancing. Raised rather than retried, because raising ``max_bytes`` is the only fix.

    Not to be confused with ``Code.RECORD_TOO_LARGE``, which is the broker refusing to **store** a
    record too big for a segment. This one is the reader's own limit, chosen by the reader.
    """

    def __init__(self, offset: int, record_bytes: int, max_bytes: int):
        self.offset = offset
        self.record_bytes = record_bytes
        self.max_bytes = max_bytes
        super().__init__(
            f"record at offset {offset} needs {record_bytes} bytes and max_bytes is {max_bytes}, "
            f"so it can never be read whole"
        )
