#!/usr/bin/env bash
#
# Asserts the two claims the first layer of the sample makes, against the running services.
#
# Both are checked from the **outside**, through the same HTTP an operator would use, rather than
# from the logs: a log line saying a record was handled is written by the code under test.

set -euo pipefail

cd "$(dirname "$0")"

PUBLISHER="${PUBLISHER_URL:-http://127.0.0.1:8080}"
CONSUMERS="${CONSUMER_URLS:-http://127.0.0.1:8081 http://127.0.0.1:8082 http://127.0.0.1:8083}"
ENOUGH="${ENOUGH_RECORDS:-15}"

# The checks below need something to have happened. On a machine that has just brought the stack up
# — CI, most of all — a partition can legitimately hold nothing yet, and then "the position did not
# survive the restart" is a true statement about a consumer that never had one. A check has to
# establish its own preconditions rather than assume them.
echo "→ waiting until every consumer has a position to lose"
for _ in $(seq 1 120); do
    ready=1
    for url in $CONSUMERS; do
        position=$(curl -fsS "$url/stats" 2>/dev/null |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["position"])' 2>/dev/null || echo 0)
        [ "${position:-0}" -gt 0 ] || ready=0
    done
    [ "$ready" = "1" ] && break
    sleep 2
done
[ "$ready" = "1" ] || { echo "::error:: some consumer never read anything — nothing to assert about"; exit 1; }

# Enough traffic that the split across partitions means something rather than being three ones.
for _ in $(seq 1 60); do
    sent=$(curl -fsS "$PUBLISHER/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sent"])')
    [ "${sent:-0}" -ge "$ENOUGH" ] && break
    sleep 1
done

# A quiescent point before anything is compared, and this is the fix for issue #12.
#
# The check reads what the publisher says it wrote and where each consumer has got to. Both numbers
# are true and they are read milliseconds apart, so a record produced in between made the two
# disagree by one — a failure on branches that do not touch `dev/` at all, passing on a re-run of
# the same commit. No length of wait fixes that: two moving numbers do not agree by being watched
# longer. Production stops, the consumers are given until they catch up, and only then is anything
# compared.
echo "→ pausing the publisher so the counts stop moving"
curl -fsS -X POST "$PUBLISHER/pause" >/dev/null

caught_up=0
for _ in $(seq 1 60); do
    behind=0
    for url in $CONSUMERS; do
        lag=$(curl -fsS "$url/stats" |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["lag"])' 2>/dev/null || echo 1)
        [ "${lag:-1}" -eq 0 ] || behind=1
    done
    [ "$behind" = "0" ] && { caught_up=1; break; }
    sleep 1
done
[ "$caught_up" = "1" ] || {
    echo "::error:: the consumers never caught up with a stopped publisher — that is a real failure"
    curl -fsS -X POST "$PUBLISHER/resume" >/dev/null || true
    exit 1
}

echo "→ every record reaches exactly one consumer"
{
    curl -fsS "$PUBLISHER/stats"
    echo
    for url in $CONSUMERS; do
        curl -fsS "$url/stats"
        echo
    done
} | python3 check.py split

# Back on before the restart check: that one needs the publisher writing while a consumer is down,
# or it would pass on a consumer that resumed at the end of an idle log.
echo "→ resuming the publisher"
curl -fsS -X POST "$PUBLISHER/resume" >/dev/null

echo
echo "→ a restarted consumer carries on rather than replaying"
# Whichever consumer has got furthest, rather than a name fixed in advance: the point is that a
# saved position survives, and any consumer that has one can demonstrate it.
read -r RESTARTED RESTARTED_URL BEFORE <<<"$(
    for url in $CONSUMERS; do
        curl -fsS "$url/stats" | URL="$url" python3 -c '
import json, os, sys
stats = json.load(sys.stdin)
print(stats["name"], os.environ["URL"], stats["position"])
'
    done | sort -k3 -n -r | head -1
)"
echo "   $RESTARTED is furthest along, at $BEFORE"
docker compose stop "$RESTARTED" >/dev/null
# Long enough that the publisher writes records the stopped consumer will have to catch up on —
# without a backlog the check would pass on a consumer that resumed at the end of the log.
sleep 8
docker compose start "$RESTARTED" >/dev/null

for _ in $(seq 1 60); do
    curl -fsS "$RESTARTED_URL/stats" >/dev/null 2>&1 && break
    sleep 1
done
sleep 5

curl -fsS "$RESTARTED_URL/stats" | python3 check.py resumed "$BEFORE"

echo
echo "✓ the work is split by partition, and a position outlives the process that keeps it"
