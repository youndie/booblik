#!/usr/bin/env bash
#
# The read model: does the view agree with the log, and does it survive being thrown away (M-111).
#
# The second question is the interesting one. This service stores nothing, so a restart has to
# rebuild the whole view by replaying — and the check is that it comes back with *at least* what it
# had, not that it comes back quickly.

set -euo pipefail

cd "$(dirname "$0")"

URL="${PROJECTION_URL:-http://127.0.0.1:8096}"

wait_for_replay() {
    for _ in $(seq 1 120); do
        done_now=$(curl -fsS "$URL/stats" 2>/dev/null |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["replayComplete"])' 2>/dev/null || echo False)
        [ "$done_now" = "True" ] && return 0
        sleep 1
    done
    echo "::error:: the projection never finished replaying"
    return 1
}

echo "→ waiting for the replay to finish"
wait_for_replay
BEFORE=$(curl -fsS "$URL/stats")

echo "→ the view agrees with what it applied"
echo "$BEFORE" | python3 check.py projection

echo
echo "→ a user answers from the view"
USER=$(curl -fsS "$URL/top?n=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["user"])')
curl -fsS "$URL/user/$USER" | python3 check.py user

curl -fsS -o /dev/null -w '   unknown user answers %{http_code}\n' "$URL/user/nobody-here" || true

echo
echo "→ throwing the whole view away and letting it rebuild"
APPLIED=$(echo "$BEFORE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["applied"])')
docker compose restart projection >/dev/null 2>&1
sleep 3
wait_for_replay

curl -fsS "$URL/stats" | APPLIED="$APPLIED" python3 check.py rebuilt

echo
echo "✓ the log is the state; the service is just a view of it"
