#!/bin/sh
# Third sampler, third file. Deliberately not a column added to sample.sh or mem.sh: editing a
# script that is already running corrupts the copy the shell reads line by line.
#
# What it answers: RssAnon grows 2.1 MB/h in a JVM whose heap, metaspace and code cache are all
# capped by the runtime profile. A total cannot say which mapping grows, so this records every
# anonymous mapping by address and lets the growth be attributed rather than guessed.
#
# Host-side only. No `docker exec` into the broker: a second JVM inside that container's cgroup is
# how the app gets killed by the tool that was sent to watch it.
OUT=/opt/booblik-soak/regions.csv
[ -f "$OUT" ] || echo "iso,addr,perms,size_kb,rss_kb" > "$OUT"
while :; do
    PID=$(docker inspect soak-broker --format '{{.State.Pid}}' 2>/dev/null)
    NOW=$(date -u +%FT%TZ)
    if [ -n "$PID" ] && [ "$PID" != "0" ]; then
        # Anonymous mappings only: a file-backed one is the log's page cache, already measured.
        pmap -X "$PID" 2>/dev/null | awk -v now="$NOW" '
            NR>2 && NF>=11 && $NF == "0" && $7+0 > 512 {
                printf "%s,%s,%s,%d,%d\n", now, $1, $2, $6, $7
            }' >> "$OUT"
    fi
    sleep 300
done
