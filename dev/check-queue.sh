#!/usr/bin/env bash
#
# Asserts what the queue claims, against the running workers.
#
# Run it after `docker compose --profile queue up -d`, and give the workers a few seconds — the
# assertions are about tasks that have already been through the log, not about a warm start.

set -euo pipefail

cd "$(dirname "$0")"

WORKERS="${WORKER_URLS:-http://127.0.0.1:8091 http://127.0.0.1:8092 http://127.0.0.1:8093}"

echo "→ every task is won by exactly one worker, and all of them agree on which"
{
    for url in $WORKERS; do
        curl -fsS "$url/stats"
        echo
    done
} | python3 check.py queue

echo
echo "✓ the order of one partition is doing the job of a coordinator"
