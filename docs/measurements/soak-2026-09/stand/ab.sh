#!/usr/bin/env bash
# A/B on identical state: the volume stays exactly as fill.sh left it (100% full, ballast in place,
# a hundred-hour log), and only the broker binary changes. 0.3.0 has just been shown going quiet;
# 0.3.1 carries the #15 fix. Anything that differs below is the fix and nothing else.
set -u
MOUNT=/mnt/booblik-soak

echo "=== state carried over, unchanged ==="
df -h "$MOUNT" | tail -1
ls -la "$MOUNT/ballast" | awk '{print "ballast:", $5, "bytes"}'

echo "=== 0.3.0 goes down (its evidence is already recorded) ==="
docker stop soak-broker >/dev/null 2>&1
docker rm soak-broker >/dev/null 2>&1

echo "=== 0.3.1 comes up on the same volume ==="
docker pull -q ghcr.io/youndie/booblik:0.3.1 >/dev/null 2>&1
START=$(date +%s%3N)
docker run -d --name soak-broker \
    --restart=no \
    -v "$MOUNT:/var/lib/booblik" \
    -p 127.0.0.1:9092:9092 \
    -e BOOBLIK_TOPICS=soak:2 \
    -e BOOBLIK_SEGMENT_CAPACITY_BYTES=16777216 \
    -e BOOBLIK_RETENTION_BYTES=268435456 \
    -e BOOBLIK_RETENTION_CHECK_MILLIS=30000 \
    ghcr.io/youndie/booblik:0.3.1 >/dev/null

UP=no
for _ in $(seq 1 90); do
    if docker logs soak-broker 2>&1 | grep -q "booblik listening"; then UP=yes; break; fi
    if [ "$(docker inspect -f '{{.State.Status}}' soak-broker)" = "exited" ]; then break; fi
    sleep 1
done
END=$(date +%s%3N)
echo "listening=$UP  after $(( END - START )) ms  (recovery of a 100-hour log on a full volume)"
docker logs soak-broker 2>&1 | grep -E "retention:|segment:|listening|Exception|refus" | head -6

echo "=== what a producer hears now ==="
docker run --rm --network host python:3.12-slim sh -c '
pip install --quiet --no-cache-dir booblik==0.1.0 >/dev/null 2>&1
python - <<PY
from booblik import Connection
from booblik.errors import BrokerError

with Connection.connect("127.0.0.1:9092", timeout=30) as c:
    for i in range(400):
        try:
            c.produce("soak", 0, [b"x" * 4096])
        except BrokerError as refusal:
            print(f"record {i}: refused, {refusal.code.name}")
            break
        except Exception as failure:
            print(f"record {i}: {type(failure).__name__}: {failure}")
            break
    else:
        print("400 records written and nothing refused")
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
docker logs --tail 5 soak-broker 2>&1 | grep -o 'booblik: in .*' | tail -1
