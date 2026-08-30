"""A reference implementation of the booblik wire protocol, in Python.

Exists so the harness can check a client without asking the client. Verifying a Go producer by
reading its records back with that same Go producer proves only that it is self-consistent — the
failure being looked for, records landing in the wrong partition or bytes coming back changed, is
invisible from inside. Something independent has to look, so this is that something.

It is also the third implementation of the protocol, after Kotlin's codec and whatever is under
test, which means it doubles as a reading of the specification by somebody who did not write the
broker. Where it disagrees with `docs/api/protocol-wire.md`, the document wins and this is a bug.

Deliberately small and blocking: no pipelining, no accumulation, one request at a time. A harness
that needed its own performance work would be a harness nobody trusts.
"""

import socket
import struct

from algorithms import crc32c

PRODUCE, FETCH, METADATA = 1, 2, 3
VERSION = 1
FETCH_VERSION = 2

ACK_NONE, ACK_WRITTEN, ACK_FORCED = 0, 1, 2

ACK_BY_NAME = {"none": ACK_NONE, "written": ACK_WRITTEN, "forced": ACK_FORCED}

ERROR_NAMES = {
    0: "NONE",
    1: "UNKNOWN_TOPIC_OR_PARTITION",
    2: "OFFSET_OUT_OF_RANGE",
    3: "RECORD_TOO_LARGE",
    4: "UNSUPPORTED_VERSION",
    5: "CORRUPT_REQUEST",
    # The partition's writer died and cannot accept writes again (M-160). The harness carries it
    # so that a client reporting it is read as the code it is rather than as UNKNOWN(6).
    6: "PARTITION_UNAVAILABLE",
}

# `[int32 length][int16 apiKey][int16 apiVersion][int32 correlationId]`, and the length counts
# everything after itself — so the header contributes 8, not 12.
REQUEST_HEADER_BYTES = 8


class ProtocolError(Exception):
    """The broker answered with an error code. Carries the name, because a number in a failure
    message sends the reader to the specification to learn what it was told."""

    def __init__(self, code: int):
        self.code = code
        self.name = ERROR_NAMES.get(code, f"UNKNOWN({code})")
        super().__init__(self.name)


class ChecksumError(Exception):
    """A record's bytes do not match the CRC stored with them.

    On the zero-copy read path the broker never touches these bytes, so nothing but a client can
    notice. A harness that skipped this would be unable to tell a corrupted log from a healthy one,
    which is the one thing the checksum exists for.
    """


class Connection:
    def __init__(self, host: str, port: int, timeout: float = 10.0):
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._correlation = 0

    def close(self):
        self.socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    # -- framing ---------------------------------------------------------------------------------

    def _send(self, api_key: int, version: int, payload: bytes) -> int:
        self._correlation += 1
        frame = struct.pack(">hhi", api_key, version, self._correlation) + payload
        self.socket.sendall(struct.pack(">i", len(frame)) + frame)
        return self._correlation

    def _receive(self, expect_correlation: int) -> bytes:
        length = struct.unpack(">i", self._read_exactly(4))[0]
        frame = self._read_exactly(length)
        correlation, error = struct.unpack(">ih", frame[:6])
        # Checked rather than assumed: the client matches responses by this number, so a response
        # carrying somebody else's id does not lose information — it **resolves the wrong request**.
        if correlation != expect_correlation:
            raise AssertionError(f"correlation {correlation} answered request {expect_correlation}")
        if error != 0:
            raise ProtocolError(error)
        return frame[6:]

    def _read_exactly(self, count: int) -> bytes:
        chunks, remaining = [], count
        while remaining:
            chunk = self.socket.recv(remaining)
            if not chunk:
                raise ConnectionError(f"broker closed the connection with {remaining} bytes to go")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    # -- requests --------------------------------------------------------------------------------

    def metadata(self, topics=()) -> dict:
        names = [t.encode("utf-8") for t in topics]
        payload = struct.pack(">i", len(names))
        for name in names:
            payload += struct.pack(">H", len(name)) + name

        body = self._receive(self._send(METADATA, VERSION, payload))

        result, cursor = {}, 4
        (topic_count,) = struct.unpack_from(">i", body, 0)
        for _ in range(topic_count):
            (name_length,) = struct.unpack_from(">H", body, cursor)
            cursor += 2
            name = body[cursor : cursor + name_length].decode("utf-8")
            cursor += name_length
            (partition_count,) = struct.unpack_from(">i", body, cursor)
            cursor += 4
            partitions = {}
            for _ in range(partition_count):
                pid, start, high = struct.unpack_from(">iqq", body, cursor)
                cursor += 20
                partitions[pid] = {"logStartOffset": start, "highWatermark": high}
            result[name] = partitions
        return result

    def produce(self, topic: str, partition: int, records, ack: int = ACK_WRITTEN):
        """Returns `(baseOffset, logEndOffset)`, or None for [ACK_NONE] — which answers nothing.

        Not an omission and not an optimisation: with no acknowledgement there is no offset yet,
        because the offset does not exist until the writer reaches the batch. A client that waits
        for a response here waits for ever, which is the single most common way to write a first
        producer in a new language.
        """
        name = topic.encode("utf-8")
        payload = struct.pack(">H", len(name)) + name
        payload += struct.pack(">ibi", partition, ack, len(records))
        for record in records:
            payload += struct.pack(">i", len(record)) + record

        correlation = self._send(PRODUCE, VERSION, payload)
        if ack == ACK_NONE:
            return None
        return struct.unpack(">qq", self._receive(correlation))

    def fetch(self, topic: str, partition: int, offset: int, max_bytes: int,
              max_wait_millis: int = 0, min_bytes: int = 0) -> dict:
        name = topic.encode("utf-8")
        payload = struct.pack(">H", len(name)) + name
        payload += struct.pack(">iqiii", partition, offset, max_bytes, max_wait_millis, min_bytes)

        body = self._receive(self._send(FETCH, FETCH_VERSION, payload))
        high_watermark, payload_bytes = struct.unpack_from(">qi", body, 0)
        records, complete_bytes = decode_records(body[12 : 12 + payload_bytes])
        return {
            "highWatermark": high_watermark,
            "records": records,
            "nextOffset": offset + len(records),
            # How much of the response was whole records. Anything past this is the truncated tail
            # a client has to drop and ask for again.
            "completeBytes": complete_bytes,
            "payloadBytes": payload_bytes,
        }


def decode_records(payload: bytes):
    """Splits a FETCH payload into records, stopping at the first incomplete one.

    A response may end in the middle of a record: `maxBytes` is a bound in bytes, not in records,
    and making it a bound in records would mean the broker parsing the batch to find the boundary —
    exactly the parse the zero-copy path exists to avoid. So the tail is the client's job, and this
    is what doing that job looks like.

    Verifies the CRC of every complete record, because on this path nothing else can.
    """
    records, cursor = [], 0
    while cursor + 8 <= len(payload):
        size, stored_crc = struct.unpack_from(">ii", payload, cursor)
        if cursor + 8 + size > len(payload):
            break  # truncated tail: ask for it again from the next offset
        body = payload[cursor + 8 : cursor + 8 + size]
        actual = crc32c(body)
        if actual != (stored_crc & 0xFFFFFFFF):
            raise ChecksumError(
                f"record at byte {cursor}: stored {stored_crc & 0xFFFFFFFF}, computed {actual}"
            )
        records.append(body)
        cursor += 8 + size
    return records, cursor


def read_all(host: str, port: int, topic: str, partition: int, start: int = 0,
             max_bytes: int = 1 << 20) -> list:
    """Every record from [start] to the high watermark, following the truncated tail properly."""
    collected = []
    with Connection(host, port) as connection:
        offset = start
        while True:
            answer = connection.fetch(topic, partition, offset, max_bytes)
            if not answer["records"]:
                return collected
            collected += answer["records"]
            offset += len(answer["records"])
            if offset >= answer["highWatermark"]:
                return collected
