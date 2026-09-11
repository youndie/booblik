#!/usr/bin/env bash
# The question the refusal raises: once the operator frees the space, does the partition come back
# on its own, or does it stay refused until the broker is restarted? The writer failure is recorded
# as permanent ("retrying does not help"), which is a statement about the batch — this asks whether
# it is also a statement about the partition, and the answer is what an operator has to be told.
set -u
MOUNT=/mnt/booblik-soak

probe() {
    docker run --rm --network host python:3.12-slim sh -c '
pip install --quiet --no-cache-dir booblik==0.1.0 >/dev/null 2>&1
python - <<PY
from booblik import Connection
from booblik.errors import BrokerError
with Connection.connect("127.0.0.1:9092", timeout=20) as c:
    try:
        c.produce("soak", 0, [b"x" * 4096])
        print("  written")
    except BrokerError as refusal:
        print(f"  refused: {refusal.code.name}")
    except Exception as failure:
        print(f"  {type(failure).__name__}: {failure}")
PY'
}

echo "=== space freed ==="
rm -f "$MOUNT/ballast"
sync
df -h "$MOUNT" | tail -1

echo "=== produce, right after freeing (same broker, not restarted) ==="
probe
sleep 35   # longer than retention.check=30s, in case the check is what revives it
echo "=== produce, after a retention check has had time to run ==="
probe

echo "=== restart the broker on the now-roomy volume ==="
docker restart soak-broker >/dev/null
for _ in $(seq 1 60); do docker logs soak-broker 2>&1 | grep -q "booblik listening" && break; sleep 1; done
echo "=== produce, after the restart ==="
probe
docker inspect -f 'broker: {{.State.Status}} health={{.State.Health.Status}} restarts={{.RestartCount}}' soak-broker
df -h "$MOUNT" | tail -1
