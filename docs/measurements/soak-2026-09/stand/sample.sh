#!/usr/bin/env bash
#
# One CSV row a minute for a hundred hours. A soak that reports at the end reports nothing when it
# dies in the middle, so everything worth a claim is written as it happens.
#
# Both sizes are recorded on purpose. `MAPPED` pre-allocates a sparse file the size of the whole
# segment, so the apparent size (what `ls` and most disk alarms read) and the blocks actually used
# (what `df` reads) disagree by design — and a "the volume plateaus" claim means nothing without
# saying which of the two it is about.

set -u
ROOT=/opt/booblik-soak
MOUNT=/mnt/booblik-soak
CSV="$ROOT/samples.csv"

if [ ! -f "$CSV" ]; then
    echo "iso,uptime_s,df_used_kb,apparent_kb,segments,broker_rss_kb,broker_fds,broker_state,restarts,produced,consumed,lag,resets,produce_errors,fetch_errors,metrics" > "$CSV"
fi

STARTED=$(date +%s)
while true; do
    NOW=$(date +%s)
    ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    DF_USED=$(df -k "$MOUNT" | awk 'NR==2 {print $3}')
    APPARENT=$(du -sk --apparent-size "$MOUNT" 2>/dev/null | awk '{print $1}')
    SEGMENTS=$(find "$MOUNT" -name '*.log' 2>/dev/null | wc -l | tr -d ' ')

    PID=$(docker inspect -f '{{.State.Pid}}' soak-broker 2>/dev/null || echo 0)
    STATE=$(docker inspect -f '{{.State.Status}}' soak-broker 2>/dev/null || echo missing)
    RESTARTS=$(docker inspect -f '{{.RestartCount}}' soak-broker 2>/dev/null || echo -1)
    if [ "${PID:-0}" -gt 0 ]; then
        RSS=$(awk '/VmRSS/ {print $2}' "/proc/$PID/status" 2>/dev/null || echo -1)
        FDS=$(ls "/proc/$PID/fd" 2>/dev/null | wc -l | tr -d ' ')
    else
        RSS=-1; FDS=-1
    fi

    LOAD=$(cat "$ROOT/state/load.json" 2>/dev/null || echo '{}')
    read -r PRODUCED CONSUMED LAG RESETS PERR FERR <<<"$(printf '%s' "$LOAD" | python3 -c '
import json, sys
try:
    s = json.load(sys.stdin)
except Exception:
    s = {}
print(s.get("produced", -1), s.get("consumed", -1), s.get("lag", -1), s.get("resets", -1),
      s.get("produce_errors", -1), s.get("fetch_errors", -1))
' 2>/dev/null || echo "-1 -1 -1 -1 -1 -1")"

    # The broker's own line, which is the thing that said `errors 0` while accepting nothing in
    # issue #15 — worth having beside numbers gathered from outside it.
    METRICS=$(docker logs --tail 40 soak-broker 2>&1 | grep -o 'booblik: in .*' | tail -1 | tr ',' ';')

    echo "$ISO,$((NOW - STARTED)),${DF_USED:--1},${APPARENT:--1},$SEGMENTS,${RSS:--1},${FDS:--1},$STATE,$RESTARTS,$PRODUCED,$CONSUMED,$LAG,$RESETS,$PERR,$FERR,\"${METRICS:-}\"" >> "$CSV"
    sleep 60
done
