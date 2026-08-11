#!/usr/bin/env bash
#
# Builds the service distributions and brings the sample up.
#
# The order matters and is the same as in the broker's own ci/docker-build.sh: the images package
# an already-built distribution, so `docker compose up --build` on its own would package whatever
# is in build/install right now — including nothing at all on a fresh checkout.

set -euo pipefail

cd "$(dirname "$0")"

echo "→ distributions"
./gradlew installDist --console=plain -q

echo "→ compose"
docker compose up --build -d

echo "→ waiting for the services to answer"
for port in 8080 8081 8082 8083; do
    for _ in $(seq 1 60); do
        curl -fsS "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break
        sleep 1
    done
    curl -fsS "http://127.0.0.1:$port/health" >/dev/null 2>&1 || {
        echo "nothing on :$port"; docker compose ps; exit 1
    }
done

echo
echo "✓ up. Watch the work spread across the three consumers:"
echo "    curl -s localhost:8080/stats   # publisher"
echo "    curl -s localhost:8081/stats   # consumer-0, partition 0"
echo "    curl -s localhost:8082/stats   # consumer-1, partition 1"
echo "    curl -s localhost:8083/stats   # consumer-2, partition 2"
