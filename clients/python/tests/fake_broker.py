"""A broker for tests: speaks the protocol well enough to answer, and **decodes what the client
encoded** rather than pattern-matching bytes.

That is the point of it. An encoding mistake becomes a decode failure here instead of a mystery
against a real broker, and the test suite needs no Docker, no network and no fixtures. It is also a
separate reading of docs/api/protocol-wire.md from the client it checks.
"""

import socket
import struct
import threading
from collections import defaultdict

from booblik.crc32c import crc32c
from booblik.wire import FETCH, METADATA, PRODUCE, RESPONSE_HEADER_BYTES, AckPolicy
from booblik.errors import Code


class FakeBroker:
    def __init__(self, partitions: int = 3):
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(8)

        self.lock = threading.Lock()
        self.produced = defaultdict(list)
        self.requests = 0
        self.next_offset = 0
        self.refuse_with = Code.NONE
        self.partitions = partitions
        self.running = True
        # Flips a bit in every stored checksum, which is what a damaged segment looks like from
        # the socket: the bytes arrive, and only the sum disagrees with them.
        self.corrupt = False
        # The apiVersion and the decoded fields of the last request, so a test can assert what
        # was actually put on the wire rather than what the client meant to put there.
        self.last_version = None
        self.last_fetch = None

        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()

    @property
    def address(self) -> str:
        host, port = self.listener.getsockname()
        return f"{host}:{port}"

    def close(self) -> None:
        self.running = False
        self.listener.close()

    def records_in(self, topic: str, partition: int) -> list:
        with self.lock:
            return list(self.produced[(topic, partition)])

    def request_count(self) -> int:
        with self.lock:
            return self.requests

    def _accept(self) -> None:
        while self.running:
            try:
                connection, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self._serve, args=(connection,), daemon=True).start()

    def _serve(self, connection: socket.socket) -> None:
        with connection:
            while True:
                header = _read_exactly(connection, 4)
                if header is None:
                    return
                frame = _read_exactly(connection, struct.unpack(">i", header)[0])
                if frame is None:
                    return

                api_key, api_version, correlation = struct.unpack_from(">hhi", frame, 0)
                payload = frame[8:]
                self.last_version = api_version

                if api_key == PRODUCE:
                    body, silent = self._produce(payload)
                    if silent:
                        continue
                elif api_key == FETCH:
                    body = self._fetch(payload)
                elif api_key == METADATA:
                    body = self._metadata(payload)
                else:
                    body = b""

                with self.lock:
                    code = int(self.refuse_with)
                response = struct.pack(">ih", correlation, code) + body
                connection.sendall(struct.pack(">i", len(response)) + response)

    def _produce(self, payload: bytes):
        """Returns the body and whether to stay silent — silence being what AckPolicy.NONE means on
        the wire, and the behaviour a client most often gets wrong."""
        cursor = 0
        (name_length,) = struct.unpack_from(">H", payload, cursor)
        cursor += 2
        topic = payload[cursor : cursor + name_length].decode("utf-8")
        cursor += name_length

        partition, ack, count = struct.unpack_from(">ibi", payload, cursor)
        cursor += 9

        records = []
        for _ in range(count):
            (size,) = struct.unpack_from(">i", payload, cursor)
            cursor += 4
            records.append(payload[cursor : cursor + size])
            cursor += size

        with self.lock:
            base = self.next_offset
            if self.refuse_with == Code.NONE:
                self.produced[(topic, partition)].extend(records)
                self.next_offset += len(records)
            self.requests += 1

        if ack == AckPolicy.NONE:
            return b"", True
        return struct.pack(">qq", base, base + len(records)), False

    def seed(self, topic: str, partition: int, *records: bytes) -> None:
        """Puts records in a partition's log without going through PRODUCE, so a fetch test states
        what is there to read instead of arranging for it.

        Offsets in this fixture are indices into that list, per partition. (The produce path keeps
        one counter for the whole broker, which is enough for what its own tests assert.)
        """
        with self.lock:
            self.produced[(topic, partition)].extend(records)

    def _fetch(self, payload: bytes) -> bytes:
        """Answers from the seeded log, framing records exactly as the disk holds them —
        payloadSize, crc32c, payload — and cutting the response at ``max_bytes`` **in bytes**,
        which is what puts a partial record at the end of a full response.

        ``max_wait`` is decoded and remembered, then ignored: nothing here can produce a record
        while a request waits, so holding one would only make the tests slower.
        """
        cursor = 0
        (name_length,) = struct.unpack_from(">H", payload, cursor)
        cursor += 2
        topic = payload[cursor : cursor + name_length].decode("utf-8")
        cursor += name_length

        partition, offset, max_bytes, max_wait, min_bytes = struct.unpack_from(">iqiii", payload, cursor)
        self.last_fetch = {
            "topic": topic,
            "partition": partition,
            "offset": offset,
            "max_bytes": max_bytes,
            "max_wait": max_wait,
            "min_bytes": min_bytes,
        }

        with self.lock:
            log = list(self.produced[(topic, partition)])
            corrupt = self.corrupt

        stream = b""
        for record in log[offset:]:
            checksum = crc32c(record) ^ (1 if corrupt else 0)
            stream += struct.pack(">iI", len(record), checksum) + record
        stream = stream[:max_bytes]

        return struct.pack(">qi", len(log), len(stream)) + stream

    def _metadata(self, payload: bytes) -> bytes:
        (count,) = struct.unpack_from(">i", payload, 0)
        cursor = 4
        names = []
        for _ in range(count):
            (name_length,) = struct.unpack_from(">H", payload, cursor)
            cursor += 2
            names.append(payload[cursor : cursor + name_length].decode("utf-8"))
            cursor += name_length
        if not names:
            names = ["everything"]

        with self.lock:
            high_watermark = self.next_offset

        body = struct.pack(">i", len(names))
        for name in names:
            encoded = name.encode("utf-8")
            body += struct.pack(">H", len(encoded)) + encoded
            body += struct.pack(">i", self.partitions)
            for partition in range(self.partitions):
                body += struct.pack(">iqq", partition, 0, high_watermark)
        return body


def _read_exactly(connection: socket.socket, count: int):
    chunks, remaining = [], count
    while remaining:
        chunk = connection.recv(remaining)
        if not chunk:
            return None
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


assert RESPONSE_HEADER_BYTES == 6, "the response header this fake writes is six bytes"
