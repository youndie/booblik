#!/usr/bin/env bash
#
# Starts a broker and runs the conformance checks against one client.
#
# Usage:
#   ./conformance/run.sh                        # checks the reference client
#   ./conformance/run.sh '<client command>'     # checks yours
#
#   BOOBLIK_IMAGE=ghcr.io/youndie/booblik:0.2.0 ./conformance/run.sh   # against a published image
#   BOOBLIK_BROKER=host:port ./conformance/run.sh '<cmd>'              # against a broker you run
#
# The broker and the checks are separate on purpose: pointing the harness at a staging broker, or
# at one inside a compose network, has to stay possible without editing this script.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT="${1:-}"
# Three partitions, not four: a power-of-two count folds on the low bits, where FNV-1a and
# `Arrays.hashCode` agree by construction, and the partitioner check loses half its power against
# the likeliest mistake. Measured and explained in conformance/harness/scenarios.py.
TOPICS="conformance:3,single:1"

if [ -z "$CLIENT" ]; then
    REFERENCE="$ROOT/booblik-conformance/build/install/booblik-conformance/bin/booblik-conformance"
    # Rebuilt when a source file is newer, not only when the binary is missing — which is what every
    # `clients/*/conformance-client.sh` already does and this did not. The difference showed up the
    # first time a check was added that the reference client had to answer: the installed
    # distribution was from before the change, so the kit reported the reference failing its own new
    # check. On one machine it happened to be missing and was rebuilt; on the other it was there.
    SOURCES="$ROOT/booblik-conformance/src $ROOT/booblik-client/src $ROOT/booblik-protocol/src"
    # shellcheck disable=SC2086
    if [ ! -x "$REFERENCE" ] || [ -n "$(find $SOURCES -name '*.kt' -newer "$REFERENCE" -print -quit 2>/dev/null)" ]; then
        echo "→ building the reference client"
        (cd "$ROOT" && ./gradlew --quiet :booblik-conformance:installDist)
    fi
    CLIENT="$REFERENCE"
    echo "→ no client given, checking the reference one"
fi

# Pointed at somebody else's broker: run and get out of the way.
if [ -n "${BOOBLIK_BROKER:-}" ]; then
    echo "→ using the broker at $BOOBLIK_BROKER (topics must be $TOPICS)"
    exec python3 "$ROOT/conformance/harness/run.py" "$CLIENT"
fi

IMAGE="${BOOBLIK_IMAGE:-booblik:conformance}"
NAME="booblik-conformance-$$"

# The host port is chosen by Docker rather than fixed here, so that `ci/gate.sh` can run one
# conformance part per client back to back without them competing for the same number.
#
# Honest note, because the comment used to claim more: this was written as the fix for two clients
# of four failing at random in a combined run, and it was **not** the cause. The real causes were a
# panic in the Go producer and a bash 4 expansion on a bash 3.2 host, both found afterwards by
# reading the output instead of guessing. A port nobody has to reserve is still the better default.
PORT="${BOOBLIK_CONFORMANCE_PORT:-}"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -z "${BOOBLIK_IMAGE:-}" ]; then
    echo "→ building image $IMAGE"
    # The name goes **to** the build rather than being tagged onto its result afterwards. Tagging
    # afterwards meant naming what `ci/docker-build.sh` happens to produce, which is `booblik:local`
    # and never was `booblik:latest`; the tag failed, `|| true` swallowed it, and the run got as far
    # as `docker run` before saying anything — at which point Docker tried the registry and reported
    # an access denial for an image that had just been built locally. Found in M-136.
    "$ROOT/ci/docker-build.sh" "$IMAGE" >/dev/null 2>&1 || {
        echo "   the image build failed; run ci/docker-build.sh to see why" >&2
        exit 1
    }
fi

echo "→ starting a broker with topics $TOPICS"
docker run -d --name "$NAME" -p "127.0.0.1:${PORT}:9092" -e "BOOBLIK_TOPICS=$TOPICS" "$IMAGE" >/dev/null

# Ask Docker which port it picked. `docker port` prints `127.0.0.1:54321`; the part after the last
# colon is what the harness needs.
PUBLISHED="$(docker port "$NAME" 9092/tcp | head -1)"
PORT="${PUBLISHED##*:}"
[ -n "$PORT" ] || {
    echo "   the broker published no port" >&2
    exit 1
}
echo "   listening on $PORT"

# Waits for the line rather than sleeping, and reads the log into a variable rather than piping it.
# Piping `docker logs` into `grep -q` exits the pipeline 141 under `set -o pipefail`: grep stops at
# the first match and `docker logs` takes SIGPIPE — a failure reported on a **successful** match.
# That cost two red runs in ci/docker-smoke.sh; a here-string has no pipe and no writer to kill.
for _ in $(seq 1 60); do
    out="$(docker logs "$NAME" 2>&1)"
    grep -q "booblik listening" <<<"$out" && break
    sleep 0.5
done
grep -q "booblik listening" <<<"$(docker logs "$NAME" 2>&1)" || {
    echo "   the broker never came up:" >&2
    docker logs "$NAME" >&2 2>&1 || true
    exit 1
}

# One untimed call before the checks start, and it is not ceremony. Every client script builds
# itself on first use, and the harness gives each verb 20 seconds — which is generous for a verb and
# nowhere near enough to link a Kotlin/Native binary on a cold runner. Locally this never showed:
# `ci/gate.sh` runs a client's own gate first, which leaves the binary built. On CI the conformance
# job is a separate runner that has never built anything, so the very first verb — `capabilities` —
# timed out and the run reported a client that "is waiting for something".
"$CLIENT" capabilities >/dev/null

BOOBLIK_BROKER="localhost:$PORT" python3 "$ROOT/conformance/harness/run.py" "$CLIENT"
