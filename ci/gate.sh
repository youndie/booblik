#!/usr/bin/env bash
#
# The gate. One command, which is project rule 1 — and from M-131а it is this script rather than
# `./gradlew check`, because Gradle cannot run `go test`.
#
# Usage:
#   ./ci/gate.sh              # everything this machine can check
#   ./ci/gate.sh jvm go       # only the named parts
#
# What it deliberately does **not** include: `ci/smoke.sh` and `ci/docker-smoke.sh` build a
# distribution and an image, which turns a gate into a coffee break, and a gate people stop running
# is not a gate. Those stay CI steps. The conformance run is here because it is the only part that
# checks a client end to end.
#
# **Exit 77 from a part means "skipped", not "passed".** It is the automake convention, and it is
# what lets a machine without a Go toolchain run this honestly: the part is named as skipped rather
# than quietly counted as green. Nothing else may exit 77.

set -uo pipefail  # not -e: every part runs, and the report is the point

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SKIPPED_CODE=77
LOGS="$(mktemp -d)"
trap 'rm -rf "$LOGS"' EXIT

PASSED=0
FAILED=""
SKIPPED=""
# What the most recent part did: passed, failed or skipped. Read by the client loop, which must not
# run a conformance check against a client whose own gate never ran — on a machine without that
# toolchain the check would fail trying to build it, reporting the client broken when what happened
# is that nobody looked. The same conflation this script had at the top level.
LAST_RESULT=""

# Without this the image build inside the conformance run would run `./gradlew check` a second time
# on the same commit. This script has already run it.
export BOOBLIK_SKIP_GATE=1

# part <name> <required-command-or-empty> <command...>
part() {
    local name="$1" toolchain="$2"
    shift 2

    LAST_RESULT=skipped
    if [ -n "$toolchain" ] && ! command -v "$toolchain" >/dev/null 2>&1; then
        printf '   －  %-14s no %s on this machine\n' "$name" "$toolchain"
        SKIPPED="$SKIPPED $name"
        return
    fi

    # The status is captured from the command itself. `if cmd; then …; fi` reports **zero** when
    # cmd fails and no else branch runs, so reading `$?` after the `if` would report every failure
    # as "exit 0" — a gate that says a thing failed successfully.
    local code=0
    if [ -t 1 ]; then
        # A terminal: keep the run readable and show the tail of whatever failed.
        "$@" >"$LOGS/$name.log" 2>&1 || code=$?
    else
        # Not a terminal, so this is CI or a log file. Stream everything: the twenty-five lines that
        # are plenty when you can re-run locally are exactly what you cannot get from a run that
        # failed once, on a runner that no longer exists.
        "$@" 2>&1 | sed 's/^/       /'
        code=${PIPESTATUS[0]}
        : >"$LOGS/$name.log"
    fi

    case "$code" in
        0)
            printf '   ✓  %-14s\n' "$name"
            PASSED=$((PASSED + 1))
            LAST_RESULT=passed
            ;;
        "$SKIPPED_CODE")
            printf '   －  %-14s skipped itself\n' "$name"
            SKIPPED="$SKIPPED $name"
            ;;
        *)
            printf '   ✗  %-14s exit %s\n' "$name" "$code"
            sed 's/^/         /' "$LOGS/$name.log" | tail -25
            FAILED="$FAILED $name"
            LAST_RESULT=failed
            ;;
    esac
}

# No arguments means everything; otherwise only the parts named on the command line.
selected() {
    local name="$1"
    shift
    [ "$#" -eq 0 ] && return 0
    for chosen in "$@"; do
        [ "$chosen" = "$name" ] && return 0
    done
    return 1
}

echo "→ booblik gate"

selected jvm "$@" && part jvm "" ./gradlew check

# Every client owns its gate and its own idea of what it needs. This loop knows nothing about any
# language, which is the point: adding a client is adding a directory.
#
# Each one is checked twice, and both are needed. Its own gate is unit tests against a fake broker
# and needs only that language's toolchain; the conformance run is the same client against a real
# broker, and it is the only thing that can catch a client which is self-consistently wrong.
for gate in clients/*/gate.sh; do
    [ -x "$gate" ] || continue
    language="$(basename "$(dirname "$gate")")"
    selected "$language" "$@" || continue

    part "$language" "" "$gate"
    # Only when its own gate actually ran and passed: a skipped toolchain cannot build a
    # conformance binary, and a client whose unit tests fail has nothing to prove against a broker.
    client="clients/$language/conformance-client.sh"
    if [ "$LAST_RESULT" = passed ] && [ -x "$client" ]; then
        part "conformance:$language" docker ./conformance/run.sh "$client"
    fi
done

# The reference client last, being the slowest and the only part that needs a broker *and* Gradle.
selected conformance "$@" && part conformance docker ./conformance/run.sh

echo
printf '   %d passed' "$PASSED"
[ -n "$FAILED" ] && printf ', failed:%s' "$FAILED"
[ -n "$SKIPPED" ] && printf ', skipped:%s' "$SKIPPED"
echo

# Nothing checked is not a pass. It is not a failure either, and conflating the two was this
# script's own first bug: `./ci/gate.sh go` on a machine without Go reported red, which says the Go
# client is broken when what happened is that nobody looked. So the convention the parts use is
# propagated upwards — 0 passed, 1 failed, 77 nothing ran.
#
# CI still goes red on 77, because any non-zero does: there the toolchains exist, so nothing having
# run means the workflow is misconfigured, which is exactly worth failing over.
if [ "$PASSED" -eq 0 ] && [ -z "$FAILED" ]; then
    echo "   nothing ran — reporting skipped (77), which is neither a pass nor a failure" >&2
    exit "$SKIPPED_CODE"
fi
[ -z "$FAILED" ]
