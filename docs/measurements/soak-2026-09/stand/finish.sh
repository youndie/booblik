#!/usr/bin/env bash
#
# Ends the soak at a hundred hours: the load stops, the sampler stops, one last row is written.
#
# The broker and the volume are deliberately **left standing**. The last act of this soak is to fill
# the volume on purpose and see what a hundred-hour-old broker answers — that is the field check for
# issue #15, and it wants the aged log rather than a fresh one. Run `fill.sh` when somebody is
# watching.

set -u
ROOT=/opt/booblik-soak

docker stop soak-load >/dev/null 2>&1
pkill -f "$ROOT/sample.sh" >/dev/null 2>&1
pkill -f "$ROOT/mem.sh" >/dev/null 2>&1

{
    echo "# stopped at $(date -u +%Y-%m-%dT%H:%M:%SZ) after $(( $(date +%s) - $(stat -c %Y "$ROOT/samples.csv") )) s of sampling"
} >> "$ROOT/finished.txt"

echo "soak finished $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$ROOT/finished.txt"

# The region sampler (regions.sh) came later than this script and is stopped here for the same
# reason as the other two: a sampler that outlives its soak writes rows nobody will be able to date.
pkill -f "/opt/booblik-soak/regions.sh" >/dev/null 2>&1
