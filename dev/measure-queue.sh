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

# Prints "wins attempts", in that order, whatever order the report happens to print them in.
# Relying on grep to preserve a field order cost a run: the report lists attempts above wins, the
# two came back swapped, and the arithmetic produced a **negative** number of lost races. Naming
# each field is the fix; measure-queue.py also refuses impossible input, so the next such mistake
# stops instead of printing.
report() {
    local out
    out="$(docker compose exec -T worker /opt/queue-worker/bin/queue-report 2>/dev/null)"
    printf '%s %s' \
        "$(awk '/claims that won/{print $NF}' <<<"$out")" \
        "$(awk '/claim attempts/{print $NF}' <<<"$out")"
}

echo "→ ${WORKERS} workers, pick=${PICK}, tasks every ${TASK_INTERVAL_MILLIS} ms, window ${WINDOW}s"
docker compose --profile swarm down -v >/dev/null 2>&1 || true
docker compose --profile swarm up -d --scale worker="$WORKERS" >/dev/null 2>&1

echo "→ waiting for the queue to actually be moving"
for _ in $(seq 1 120); do
    read -r wins _ <<<"$(report || echo '0 0')"
    [ "${wins:-0}" -gt 20 ] && break
    sleep 2
done
[ "${wins:-0}" -gt 20 ] || { echo "the queue never started moving"; docker compose ps; exit 1; }

read -r WINS0 ATTEMPTS0 <<<"$(report)"
sleep "$WINDOW"
read -r WINS1 ATTEMPTS1 <<<"$(report)"

LATENCY=$(docker compose exec -T worker sh -c 'wget -qO- localhost:8080/stats' 2>/dev/null)

WINS0="$WINS0" WINS1="$WINS1" ATTEMPTS0="$ATTEMPTS0" ATTEMPTS1="$ATTEMPTS1" \
    PICK="$PICK" WORKERS="$WORKERS" WINDOW="$WINDOW" \
    python3 measure-queue.py <<<"$LATENCY"
