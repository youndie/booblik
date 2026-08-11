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
RESTARTED="${RESTARTED_CONSUMER:-consumer-1}"
RESTARTED_URL="${RESTARTED_CONSUMER_URL:-http://127.0.0.1:8082}"

echo "→ every record reaches exactly one consumer"
{
    curl -fsS "$PUBLISHER/stats"
    echo
    for url in $CONSUMERS; do
        curl -fsS "$url/stats"
        echo
    done
} | python3 check.py split

echo
echo "→ a restarted consumer carries on rather than replaying"
BEFORE=$(curl -fsS "$RESTARTED_URL/stats" | python3 -c 'import json,sys; print(json.load(sys.stdin)["position"])')
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
