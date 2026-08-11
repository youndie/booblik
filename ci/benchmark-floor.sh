#!/usr/bin/env bash
#
# Checks that performance has not collapsed by an order of magnitude.
#
# This is **not** a regression detector, and the difference matters. Rule 3 of docs/benchmarking.md
# forbids comparing runs from different sessions: a control measurement in M-44 differed from the
# same configuration on the same machine by 37 %, simply on another day. On a rented runner, where
# every run is a different machine with different neighbours, any threshold expressed in per cent
# would either fire constantly or never fire at all.
#
# So the floor here is **an order of magnitude below** the slowest plausible runner. It catches
# "somebody made writes a hundred times slower" and deliberately catches nothing finer. A check that
# catches less than it promises is worse than no check: people start relying on it.
#
# Measured values from real machines, for calibration (docs/benchmarking.md, measurement 10):
#   FILE_CHANNEL, 64 B, no flush: 215,206 (Apple M1) … 2,465,259 (20 cores, WSL2)
#   MAPPED,       64 B, no flush: 13.2M (Apple M1) … 37.6M (20 cores, WSL2)

set -euo pipefail

REPORT="${1:?pass the file holding the benchmark output}"

# A tenth of the slowest we have seen.
FLOOR_FILE_CHANNEL=20000
FLOOR_MAPPED=1000000

fail=0

score() {
    # A line of the form:
    #   SegmentAppendBenchmark.append  N/A  N/A  false  FILE_CHANNEL  64  N/A  thrpt  10  215205.868 ± ...
    # Take the field before the "±". JMH prints the number with a dot whatever the locale is.
    awk -v mode="$1" '
        /SegmentAppendBenchmark.append/ && $0 ~ mode && / false / {
            for (i = 1; i <= NF; i++) if ($(i + 1) == "±") { print $i; exit }
        }
    ' "$REPORT"
}

check() {
    local label="$1" mode="$2" floor="$3"
    local value
    value="$(score "$mode")"
    if [ -z "$value" ]; then
        echo "✗ $label: no such line in the report — the benchmark did not run"
        fail=1
        return
    fi
    # Integer part: bash has no fractions, and precision buys nothing at this scale.
    local whole="${value%%.*}"
    if [ "$whole" -lt "$floor" ]; then
        echo "✗ $label: $whole records/s — below the floor of $floor, this is a collapse, not a regression"
        fail=1
    else
        echo "✓ $label: $whole records/s (floor $floor)"
    fi
}

check "FILE_CHANNEL, 64 B, no flush" "FILE_CHANNEL *64 " "$FLOOR_FILE_CHANNEL"
check "MAPPED, 64 B, no flush" "MAPPED *64 " "$FLOOR_MAPPED"

if [ "$fail" -ne 0 ]; then
    echo
    echo "The floor is a tenth of the slowest host measured. If it fired, look at what broke"
    echo "rather than at percentages: numbers from different CI runs are not comparable at all"
    echo "(docs/benchmarking.md, rule 3)."
    exit 1
fi

echo "Floor passed. That means only 'nothing collapsed': these numbers cannot be compared"
echo "with the previous CI run — different machine."
