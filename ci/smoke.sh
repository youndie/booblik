#!/usr/bin/env bash
#
# Запускает собранный брокер и разговаривает с ним по сокету.
#
# Зачем отдельно от тестов: `check` проверяет код, а это проверяет **поставку**. Между ними
# помещается целый класс поломок — конфигурация не читается, `main` падает на старте, скрипт
# запуска ссылается на несуществующий класс, — и ни одну из них тесты не видят, потому что тесты
# поднимают сервер из кода, а не из дистрибутива.
#
# Этим же способом был найден единственный настоящий баг M5: писатель терял батч, если таймер
# сброса срабатывал одновременно с приходом сообщения. Юнит-тест его не воспроизводил, а вот
# продюсер по сокету честно завис.

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

echo "→ сборка дистрибутива"
"$ROOT/gradlew" -p "$ROOT" :booblik-app:installDist --console=plain -q

echo "→ старт"
"$ROOT/booblik-app/build/install/booblik-app/bin/booblik-app" "$WORK/broker.properties" > "$WORK/broker.log" 2>&1 &
BROKER=$!
# Убить брокер надо в любом случае, включая падение проверки ниже: иначе CI-джоба висит до
# таймаута, а локальный запуск оставляет процесс на порту.
trap 'kill "$BROKER" 2>/dev/null || true; rm -rf "$WORK"' EXIT

for _ in $(seq 1 50); do
    grep -q "booblik listening" "$WORK/broker.log" && break
    sleep 0.2
done
grep -q "booblik listening" "$WORK/broker.log" || { echo "брокер не поднялся:"; cat "$WORK/broker.log"; exit 1; }

echo "→ PRODUCE и FETCH по проводу"
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
print(f"   {len(records)} записей записаны, прочитаны побайтово и сошлись по контрольной сумме")
PY

echo "→ рестарт: лог должен пережить остановку"
kill "$BROKER"
wait "$BROKER" 2>/dev/null || true
"$ROOT/booblik-app/build/install/booblik-app/bin/booblik-app" "$WORK/broker.properties" > "$WORK/broker2.log" 2>&1 &
BROKER=$!
for _ in $(seq 1 50); do
    grep -q "booblik listening" "$WORK/broker2.log" && break
    sleep 0.2
done
grep -q "smoke-0: offsets 0..64" "$WORK/broker2.log" || {
    echo "после рестарта лог не восстановился:"; cat "$WORK/broker2.log"; exit 1
}
echo "   восстановлено 64 записи"

echo "✓ дистрибутив запускается, обслуживает и переживает рестарт"
