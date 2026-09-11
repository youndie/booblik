#!/usr/bin/env bash
#
# The memory breakdown, sampled separately — and separately on purpose.
#
# `sample.sh` is already running, and editing a bash script while bash is reading it corrupts the
# run: the interpreter reads from the file as it goes. So this is a second file rather than a
# column added to the first.
#
# It exists because ten hours in, the total RSS looked like slow growth — 615 to 638 MiB with a
# sawtooth — and the breakdown says otherwise: 103 MiB anonymous against 511 MiB of mapped segment,
# which is the retention budget exactly. The mapping warms to the size of the window and stops. The
# number that would say "this leaks" is the anonymous one, and that is what this samples.
#
# The same lesson was paid for on another project's soak: alert on anon, never on the total, or the
# alarm fires on a page cache doing its job.

set -u
ROOT=/opt/booblik-soak
CSV="$ROOT/memory.csv"

[ -f "$CSV" ] || echo "iso,uptime_s,rss_kb,anon_kb,file_kb,swap_kb,threads,host_avail_mb" > "$CSV"

STARTED=$(date +%s)
while true; do
    PID=$(docker inspect -f '{{.State.Pid}}' soak-broker 2>/dev/null || echo 0)
    if [ "${PID:-0}" -gt 0 ] && [ -r "/proc/$PID/status" ]; then
        RSS=$(awk '/^VmRSS/ {print $2}' "/proc/$PID/status")
        ANON=$(awk '/^RssAnon/ {print $2}' "/proc/$PID/status")
        FILE=$(awk '/^RssFile/ {print $2}' "/proc/$PID/status")
        SWAP=$(awk '/^VmSwap/ {print $2}' "/proc/$PID/status")
        THREADS=$(awk '/^Threads/ {print $2}' "/proc/$PID/status")
    else
        RSS=-1; ANON=-1; FILE=-1; SWAP=-1; THREADS=-1
    fi
    AVAIL=$(free -m | awk 'NR==2 {print $7}')

    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$(( $(date +%s) - STARTED )),$RSS,$ANON,$FILE,$SWAP,$THREADS,$AVAIL" >> "$CSV"
    sleep 60
done
