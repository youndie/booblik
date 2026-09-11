#!/usr/bin/env bash
# The same field case that produced the crash, replayed through the path a user takes: the client
# installed from PyPI, not the working copy. 0.1.0 answered "6 is not a valid Code" here.
set -u
MOUNT=/mnt/booblik-soak

dd if=/dev/zero of="$MOUNT/ballast" bs=1M 2>/dev/null
df -h "$MOUNT" | tail -1

for VERSION in 0.1.0 0.1.1; do
    echo "=== booblik==$VERSION, installed from PyPI ==="
    docker run --rm --network host python:3.12-slim sh -c "
pip install --quiet --no-cache-dir booblik==$VERSION >/dev/null 2>&1
python - <<PY
from booblik import Connection
from booblik.errors import BrokerError
with Connection.connect('127.0.0.1:9092', timeout=20) as c:
    try:
        c.produce('soak', 0, [b'x' * 4096])
        print('  written')
    except BrokerError as refusal:
        print(f'  refused: {refusal.code.name} (code {int(refusal.code)}) — {refusal}')
        c.metadata('soak')
        print('  and the connection still answers METADATA afterwards')
    except Exception as failure:
        print(f'  {type(failure).__name__}: {failure}')
PY"
done

echo "=== stand back to usable ==="
rm -f "$MOUNT/ballast"
docker restart soak-broker >/dev/null
for _ in $(seq 1 90); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' soak-broker)" = "healthy" ] && break
    sleep 2
done
docker inspect -f 'broker: {{.State.Status}} health={{.State.Health.Status}}' soak-broker
df -h "$MOUNT" | tail -1
