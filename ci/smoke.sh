#!/usr/bin/env bash
#
# Starts the built broker and talks to it over a socket.
#
# Why this exists next to the tests: `check` checks the code, this checks the **delivery**. A whole
# class of failures lives between the two — configuration is not read, `main` dies at startup, the
# start script names a class that is not there — and the tests see none of them, because they bring
# a server up from code rather than from the distribution.
#
# This is also how the one real bug of M5 was found: the writer lost a batch when the flush timer
# fired at the same moment a message arrived. No unit test reproduced it; a producer over a socket
# simply hung.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT="${BOOBLIK_SMOKE_PORT:-19099}"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/broker.properties" <<EOF
booblik.data.dir=$WORK/data
booblik.port=$PORT
booblik.topics=smoke:1
booblik.segment.capacity.bytes=1048576
booblik.flush.every.millis=100
booblik.metrics.interval.millis=1000
EOF

echo "→ building the distribution"
"$ROOT/gradlew" -p "$ROOT" :booblik-app:installDist --console=plain -q

echo "→ starting"
"$ROOT/booblik-app/build/install/booblik-app/bin/booblik-app" "$WORK/broker.properties" > "$WORK/broker.log" 2>&1 &
BROKER=$!
# The broker has to be killed whatever happens below, failed check included: otherwise a CI job
# hangs until its timeout and a local run leaves a process sitting on the port.
trap 'kill "$BROKER" 2>/dev/null || true; rm -rf "$WORK"' EXIT

for _ in $(seq 1 50); do
    grep -q "booblik listening" "$WORK/broker.log" && break
    sleep 0.2
done
grep -q "booblik listening" "$WORK/broker.log" || { echo "the broker did not come up:"; cat "$WORK/broker.log"; exit 1; }

echo "→ PRODUCE and FETCH over the wire"
python3 - "$PORT" <<'PY'
import socket, struct, sys

# CRC32C (Castagnoli) — the same polynomial java.util.zip.CRC32C uses. Written out rather than
# imported because the point of this script is to read the format with code that shares nothing
# with the implementation.
_TABLE = []
for _i in range(256):
    _c = _i
    for _ in range(8):
        _c = (_c >> 1) ^ (0x82F63B78 if _c & 1 else 0)
    _TABLE.append(_c)


def crc32c(data: bytes) -> int:
    crc = 0xFFFFFFFF
    for byte in data:
        crc = (crc >> 8) ^ _TABLE[(crc ^ byte) & 0xFF]
    crc ^= 0xFFFFFFFF
    # The broker stores it as a signed int32.
    return crc - (1 << 32) if crc >= (1 << 31) else crc


port = int(sys.argv[1])
topic = b"smoke"
sock = socket.create_connection(("127.0.0.1", port), timeout=15)


def frame(body: bytes) -> bytes:
    return struct.pack(">i", len(body)) + body


def read_frame() -> bytes:
    size = struct.unpack(">i", sock.recv(4))[0]
    buf = b""
    while len(buf) < size:
        buf += sock.recv(size - len(buf))
    return buf


records = [f"record-{i}".encode() for i in range(64)]
body = struct.pack(">hhi", 1, 1, 1) + struct.pack(">h", len(topic)) + topic
body += struct.pack(">i", 0) + bytes([1]) + struct.pack(">i", len(records))
for record in records:
    body += struct.pack(">i", len(record)) + record
sock.sendall(frame(body))

response = read_frame()
correlation, error = struct.unpack(">ih", response[:6])
base, end = struct.unpack(">qq", response[6:22])
assert correlation == 1, f"PRODUCE echoed correlation {correlation}"
assert error == 0, f"PRODUCE failed with error {error}"
assert end - base == len(records), f"expected {len(records)} offsets, got {end - base}"

body = struct.pack(">hhi", 2, 1, 2) + struct.pack(">h", len(topic)) + topic
body += struct.pack(">i", 0) + struct.pack(">q", base) + struct.pack(">i", 1 << 20)
sock.sendall(frame(body))

response = read_frame()
correlation, error = struct.unpack(">ih", response[:6])
assert correlation == 2, f"FETCH echoed correlation {correlation}"
assert error == 0, f"FETCH failed with error {error}"
# `[int32 size][int32 crc32c][payload]`. Parsed independently of the Kotlin client on purpose:
# this script is the only place the wire format is read by code that does not share its
# implementation, which is how it caught the header growing from four bytes to eight.
payload = response[18:]
read, offset = [], 0
while offset + 8 <= len(payload):
    size, crc = struct.unpack(">ii", payload[offset:offset + 8])
    offset += 8
    record = payload[offset:offset + size]
    offset += size
    # The broker computes this on write; the client is the only party on the read path that can
    # check it, because the zero-copy path never touches the bytes.
    read.append((record, crc))
assert [r for r, _ in read] == records, f"read back {len(read)} records, not the {len(records)} written"
for index, (record, crc) in enumerate(read):
    expected = crc32c(record)
    assert expected == crc, f"record {index} checksum {crc} does not match {expected}"
sock.close()
print(f"   {len(records)} records written, read back byte for byte and matched their checksums")
PY

echo "→ restart: the log has to survive a stop"
kill "$BROKER"
wait "$BROKER" 2>/dev/null || true
"$ROOT/booblik-app/build/install/booblik-app/bin/booblik-app" "$WORK/broker.properties" > "$WORK/broker2.log" 2>&1 &
BROKER=$!
for _ in $(seq 1 50); do
    grep -q "booblik listening" "$WORK/broker2.log" && break
    sleep 0.2
done
grep -q "smoke-0: offsets 0..64" "$WORK/broker2.log" || {
    echo "the log did not come back after the restart:"; cat "$WORK/broker2.log"; exit 1
}
echo "   64 records recovered"

echo "✓ the distribution starts, serves and survives a restart"
