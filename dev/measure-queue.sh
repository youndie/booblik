#!/usr/bin/env bash
#
# What the queue costs, measured over a fixed window (M-103).
#
# The window is the whole point. A first attempt at this compared two runs by whatever had
# accumulated in the log by the time the report ran, and the numbers came out 1 task against 1711 —
# not because the strategies differ that much, but because bringing up thirty-one containers takes
# longer the first time and the two runs were not the same length. Snapshotting the log at the
# start and the end of a fixed window removes both the startup and the question.
#
# Usage:
#   ./measure-queue.sh first 30 60      # strategy, workers, window in seconds
#
# For a number worth writing down, point it at a broker built for this machine:
#   BOOBLIK_IMAGE=booblik:native BOOBLIK_PLATFORM=linux/arm64 ./measure-queue.sh first 30 60

set -euo pipefail

cd "$(dirname "$0")"

PICK="${1:-first}"
WORKERS="${2:-3}"
WINDOW="${3:-60}"
export WORKER_PICK="$PICK"
export TASK_INTERVAL_MILLIS="${TASK_INTERVAL_MILLIS:-10}"

report() {
    docker compose exec -T worker /opt/queue-worker/bin/queue-report 2>/dev/null |
        grep -E 'claims that won|claim attempts' |
        awk '{print $NF}' |
        paste -sd' ' -
}

echo "→ ${WORKERS} workers, pick=${PICK}, tasks every ${TASK_INTERVAL_MILLIS} ms, window ${WINDOW}s"
docker compose --profile swarm down -v >/dev/null 2>&1 || true
docker compose --profile swarm up -d --scale worker="$WORKERS" >/dev/null 2>&1

echo "→ waiting for the queue to actually be moving"
for _ in $(seq 1 120); do
    read -r finished _ <<<"$(report || echo '0 0')"
    [ "${finished:-0}" -gt 20 ] && break
    sleep 2
done
[ "${finished:-0}" -gt 20 ] || { echo "the queue never started moving"; docker compose ps; exit 1; }

read -r FINISHED0 ATTEMPTS0 <<<"$(report)"
sleep "$WINDOW"
read -r FINISHED1 ATTEMPTS1 <<<"$(report)"

LATENCY=$(docker compose exec -T worker sh -c 'wget -qO- localhost:8080/stats' 2>/dev/null)

FINISHED0="$FINISHED0" FINISHED1="$FINISHED1" ATTEMPTS0="$ATTEMPTS0" ATTEMPTS1="$ATTEMPTS1" \
    PICK="$PICK" WORKERS="$WORKERS" WINDOW="$WINDOW" \
    python3 measure-queue.py <<<"$LATENCY"
