"""Building and parsing frames — bytes in, bytes out, and no socket anywhere.

Split out of ``connection`` when the asyncio client arrived (M-133а). The seam is the same one the
Kotlin/Native milestone cut along: **building bytes** and **moving bytes** are different jobs, and
only the second of them has anything to do with whether the caller is synchronous. Without this
split the asyncio client would have been a second copy of the codec, drifting at exactly the rate
nobody notices — the tests would keep passing against whichever copy the tests use.

The wire format itself is ``docs/api/protocol-wire.md``; where this disagrees with it, this is wrong.
"""

from __future__ import annotations

import struct
from enum import IntEnum
from typing import NamedTuple

from .crc32c import crc32c
from .errors import BrokerError, Code, CorruptRecordError, ProtocolError

PRODUCE, FETCH, METADATA = 1, 2, 3
VERSION = 1

#: FETCH alone has a v2, which adds maxWaitMillis and minBytes. Always emitted, including when
#: nothing is being waited for: one code path, rather than a v1 branch that only the caller who
#: never sets a wait would ever exercise. The broker still decodes v1 for anybody else's client.
FETCH_VERSION = 2

#: payloadSize and crc32c, in front of every record inside a FETCH response — the on-disk format
#: unchanged, which is what lets the broker send segment bytes without touching them.
RECORD_HEADER_BYTES = 4 + 4

#: What the length prefix counts before the payload: apiKey, apiVersion, correlationId.
REQUEST_HEADER_BYTES = 2 + 2 + 4

#: correlationId and errorCode, on every response.
RESPONSE_HEADER_BYTES = 4 + 2

LENGTH_PREFIX_BYTES = 4

#: A length prefix off a socket is whatever the other end said, and allocating what it asks for is
#: how a process dies to one packet.
MAX_FRAME_BYTES = 8 << 20


class AckPolicy(IntEnum):
    """How long the broker waits before answering."""

    #: Answers nothing at all — not an empty response, nothing, because no offset exists until the
    #: writer reaches the batch. It is also the only mode in which the broker may lose an accepted
    #: record without the client hearing about it, or about being overloaded.
    NONE = 0
    #: Answers once the record is in the log, before any durability barrier.
    WRITTEN = 1
    #: Answers after force(). Not one barrier per request: the broker groups everyone already
    #: queued into one.
    FORCED = 2


class PartitionInfo(NamedTuple):
    partition: int
    #: Where the *live* log begins, after retention. Reading "from the start" means starting here
    #: and not at zero — zero is OFFSET_OUT_OF_RANGE on any topic that has ever dropped a segment.
    log_start_offset: int
    #: The first offset that does not exist yet.
    high_watermark: int


class ProduceResult(NamedTuple):
    base_offset: int
    log_end_offset: int


class Fetched(NamedTuple):
    """One FETCH response, unframed and checksum-verified."""

    #: The first offset that does not exist yet, as of this response.
    high_watermark: int
    records: list[bytes]
    #: True when the response ended inside a record. Routine rather than exceptional: ``max_bytes``
    #: cuts on a byte boundary, so a full response normally ends this way.
    truncated: bool
    #: How big the dropped record is, when its header made it into the response; 0 when the
    #: response stopped inside the header itself.
    truncated_record_bytes: int


def frame(api_key: int, version: int, correlation: int, payload: bytes) -> bytes:
    """One request, length prefix included, so a caller writes one thing to one socket."""
    body = struct.pack(">hhi", api_key, version, correlation) + payload
    return struct.pack(">i", len(body)) + body


def encode_produce(
    correlation: int,
    topic: str,
    partition: int,
    records: list[bytes],
    ack: AckPolicy,
) -> bytes:
    name = topic.encode("utf-8")
    payload = struct.pack(">H", len(name)) + name
    payload += struct.pack(">ibi", partition, int(ack), len(records))
    for record in records:
        payload += struct.pack(">i", len(record)) + record
    return frame(PRODUCE, VERSION, correlation, payload)


def encode_fetch(
    correlation: int,
    topic: str,
    partition: int,
    offset: int,
    max_bytes: int,
    max_wait_millis: int = 0,
    min_bytes: int = 0,
) -> bytes:
    name = topic.encode("utf-8")
    payload = struct.pack(">H", len(name)) + name
    payload += struct.pack(">iqiii", partition, offset, max_bytes, max_wait_millis, min_bytes)
    return frame(FETCH, FETCH_VERSION, correlation, payload)


def decode_fetch(body: bytes, offset: int) -> Fetched:
    """Unframes the records and verifies every checksum.

    ``offset`` is the one asked for, and it is here only so that a failure can say *which* record
    is damaged rather than that one of them is.
    """
    if len(body) < 12:
        raise ProtocolError(f"FETCH response is {len(body)} bytes, expected at least 12")

    high_watermark, promised = struct.unpack_from(">qi", body, 0)
    payload = body[12:]

    # The frame length already bounds the payload, so this field is redundant — which is exactly
    # what makes it worth checking. It is computed before the transfer starts, while the bytes
    # arrive afterwards from ``transferTo`` in an unpredictable number of pieces; a disagreement
    # means the two halves of the response came from different states of the log.
    if promised != len(payload):
        raise ProtocolError(f"FETCH promised {promised} payload bytes and the frame carries {len(payload)}")

    records: list[bytes] = []
    cursor = 0
    while len(payload) - cursor >= RECORD_HEADER_BYTES:
        size, stored = struct.unpack_from(">ii", payload, cursor)
        # struct's ">i" is signed, and the checksum on the wire is not: 0x82F63B78 and anything
        # above it arrive negative. Compared unsigned, or half the records fail a sum they match.
        stored &= 0xFFFFFFFF

        # A whole header is either there or not — parsing always resumes on a record boundary — so
        # a non-positive size is a malformed frame rather than a truncated tail. Empty records
        # cannot be stored at all, which is why the broker refuses them.
        if size <= 0:
            raise ProtocolError(f"record header at offset {offset + len(records)} says {size} bytes")
        if size > len(payload) - cursor - RECORD_HEADER_BYTES:
            return Fetched(high_watermark, records, True, size)

        start = cursor + RECORD_HEADER_BYTES
        record = payload[start : start + size]
        # After the length check and never before it: a truncated tail is not corruption, and
        # reporting it as such would turn the most ordinary response there is into an alarm.
        computed = crc32c(record)
        if computed != stored:
            raise CorruptRecordError(offset + len(records), stored, computed)
        records.append(record)
        cursor = start + size

    # Fewer bytes left than a record header: the response stopped inside the header of the next
    # record, which is the same truncation with nothing to say about its size.
    return Fetched(high_watermark, records, cursor < len(payload), 0)


def encode_metadata(correlation: int, topics: tuple[str, ...]) -> bytes:
    names = [topic.encode("utf-8") for topic in topics]
    payload = struct.pack(">i", len(names))
    for name in names:
        payload += struct.pack(">H", len(name)) + name
    return frame(METADATA, VERSION, correlation, payload)


def frame_length(prefix: bytes) -> int:
    """The declared length of a response, refused if it is not one this client will allocate."""
    length = struct.unpack(">i", prefix)[0]
    if length < RESPONSE_HEADER_BYTES or length > MAX_FRAME_BYTES:
        raise ProtocolError(f"response frame length {length} is out of range")
    return length


def read_header(frame_bytes: bytes, expect: int) -> bytes:
    """Checks the correlation id and the error code, and returns what is left.

    The identifier is *checked* rather than trusted. A response carrying somebody else's correlation
    id does not merely lose information — it **resolves the wrong request**, handing one caller
    another caller's offsets.
    """
    correlation, code = struct.unpack_from(">ih", frame_bytes, 0)
    if correlation != expect:
        raise ProtocolError(f"response {correlation} answered request {expect}")
    if code != Code.NONE:
        raise BrokerError(Code(code))
    return frame_bytes[RESPONSE_HEADER_BYTES:]


def decode_produce(body: bytes) -> ProduceResult:
    return ProduceResult(*struct.unpack(">qq", body))


def decode_metadata(body: bytes) -> dict[str, list[PartitionInfo]]:
    cursor = 4
    (topic_count,) = struct.unpack_from(">i", body, 0)
    result: dict[str, list[PartitionInfo]] = {}

    try:
        for _ in range(topic_count):
            (name_length,) = struct.unpack_from(">H", body, cursor)
            cursor += 2
            name = body[cursor : cursor + name_length].decode("utf-8")
            cursor += name_length

            (partition_count,) = struct.unpack_from(">i", body, cursor)
            cursor += 4

            partitions = []
            for _ in range(partition_count):
                partitions.append(PartitionInfo(*struct.unpack_from(">iqq", body, cursor)))
                cursor += 20
            result[name] = partitions
    except struct.error as failure:
        # A response cut short by a broker restart is a connection problem, not a crash in the
        # caller's process.
        raise ProtocolError(f"METADATA response ends early: {failure}") from failure
    return result
