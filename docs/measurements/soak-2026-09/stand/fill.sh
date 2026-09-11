#!/usr/bin/env bash
#
# The last act: fill the volume under a broker that has been writing for a hundred hours, and see
# what it answers.
#
# Before M-160 the answer was nothing at all — the batch in flight was never answered, the next
# producer had its connection dropped mid-response, and the health check stayed green while the
# broker accepted nothing. The fix says `PARTITION_UNAVAILABLE`, and it has been shown on a
# freshly-started container; this is the same thing on a log with a hundred hours behind it.

set -u
MOUNT=/mnt/booblik-soak

echo "=== before ==="
df -h "$MOUNT" | tail -1
docker inspect -f 'broker: {{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restarts={{.RestartCount}}' soak-broker

echo "=== filling what retention leaves free ==="
dd if=/dev/zero of="$MOUNT/ballast" bs=1M 2>/dev/null
df -h "$MOUNT" | tail -1

echo "=== what a producer hears now ==="
docker run --rm --network host \
    -v /opt/booblik-soak/state:/state \
    python:3.12-slim sh -c '
pip install --quiet --no-cache-dir booblik==0.1.0 >/dev/null 2>&1
python - <<PY
from booblik import Connection
from booblik.errors import BrokerError

with Connection.connect("127.0.0.1:9092", timeout=30) as c:
    for i in range(400):
        try:
            c.produce("soak", 0, [b"x" * 4096])
        except BrokerError as refusal:
            print(f"record {i}: {refusal.code.name}")
            break
        except Exception as failure:
            print(f"record {i}: {type(failure).__name__}: {failure}")
            break
    else:
        print("400 records written and nothing refused — retention still had room")
PY'

echo "=== and can it still be read? ==="
docker run --rm --network host python:3.12-slim sh -c '
pip install --quiet --no-cache-dir booblik==0.1.0 >/dev/null 2>&1
python - <<PY
from booblik import Connection
with Connection.connect("127.0.0.1:9092", timeout=30) as c:
    info = c.metadata("soak")["soak"][0]
    answer = c.fetch("soak", 0, info.log_start_offset, 1 << 20)
    print(f"read {len(answer.records)} records from offset {info.log_start_offset}")
PY'

echo "=== broker after ==="
docker inspect -f 'broker: {{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}} streak={{.State.Health.FailingStreak}}{{else}}none{{end}}' soak-broker
docker logs --tail 3 soak-broker 2>&1 | grep -o 'booblik: in .*' | tail -1
