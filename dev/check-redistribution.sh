#!/usr/bin/env bash
#
# Kills a worker while it is holding a task, and shows the task finished by somebody else (M-104).
#
# This is the claim the queue exists to make. The first layer of the sample cannot make it: there,
# a partition belongs to one consumer and nobody takes it over. Here the lease in the claims log
# lapses and the task becomes claimable again — by the same rule that decided who had it, replayed
# from the same records.
#
# SIGKILL rather than a graceful stop, on purpose. A worker that shuts down cleanly could release
# its claim; the interesting case is the one that cannot.

set -euo pipefail

cd "$(dirname "$0")"

# Short enough to watch, longer than the work, so a killed worker leaves a task genuinely held.
export WORKER_LEASE_MILLIS="${WORKER_LEASE_MILLIS:-8000}"
export WORKER_WORK_MILLIS="${WORKER_WORK_MILLIS:-4000}"
WORKERS=("worker-0 8091" "worker-1 8092" "worker-2 8093")

echo "→ starting three workers (lease ${WORKER_LEASE_MILLIS} ms, work ${WORKER_WORK_MILLIS} ms)"
docker compose --profile queue up -d >/dev/null 2>&1

for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:8091/stats" >/dev/null 2>&1 && break
    sleep 1
done

echo "→ waiting for somebody to be holding a task"
VICTIM=""
for _ in $(seq 1 60); do
    for entry in "${WORKERS[@]}"; do
        read -r name port <<<"$entry"
        held=$(curl -fsS "http://127.0.0.1:$port/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["current"] or "")')
        if [ -n "$held" ]; then
            VICTIM="$name"
            TASK="$held"
            VICTIM_PORT="$port"
            break 2
        fi
    done
    sleep 1
done
[ -n "$VICTIM" ] || { echo "nobody ever held a task"; docker compose ps; exit 1; }

# Asked of a **surviving** worker: the victim is about to stop answering, and the question is what
# the rest of the system concluded, not what the dead one believed.
SURVIVOR_PORT=8091
[ "$VICTIM_PORT" = "8091" ] && SURVIVOR_PORT=8092

echo "   $VICTIM is holding task $TASK"
echo "→ killing $VICTIM outright"
docker compose kill "$VICTIM" >/dev/null 2>&1

DEADLINE=$(( WORKER_LEASE_MILLIS / 1000 + 30 ))
echo "→ waiting up to ${DEADLINE}s for task $TASK to be finished by somebody else"
for _ in $(seq 1 "$DEADLINE"); do
    state=$(curl -fsS "http://127.0.0.1:$SURVIVOR_PORT/task/$TASK")
    done_now=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["done"])' <<<"$state")
    [ "$done_now" = "True" ] && break
    sleep 1
done

echo "$state" | VICTIM="$VICTIM" TASK="$TASK" python3 check.py redistributed

echo
echo "✓ a lease that lapses in the log is enough to hand the work on"
