#!/usr/bin/env bash
#
# Builds the image from a distribution that **passed the gate**.
#
# That is the only difference from a plain `docker build .`, and it matters: the image has no build
# stage, so `docker build` on its own takes whatever is sitting in `booblik-app/build/install` right
# now — a month old, or from a branch with failing tests. The order here is the guarantee.
#
# Usage:
#   ./ci/docker-build.sh                 # booblik:local
#   ./ci/docker-build.sh booblik:1.0     # a name of your own
#   BOOBLIK_SKIP_GATE=1 ./ci/docker-build.sh   # for debugging the image itself, nothing else

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${1:-booblik:local}"

GATED="from a distribution that passed the gate"
if [ -z "${BOOBLIK_SKIP_GATE:-}" ]; then
    echo "→ gate"
    "$ROOT/gradlew" -p "$ROOT" check --console=plain -q
else
    echo "! gate skipped through BOOBLIK_SKIP_GATE"
    GATED="WITHOUT THE GATE — from unchecked code"
fi

echo "→ distribution"
"$ROOT/gradlew" -p "$ROOT" :booblik-app:installDist --console=plain -q

echo "→ image $IMAGE"
docker build -t "$IMAGE" "$ROOT"
# Not `docker images ... | head -1`: under `set -o pipefail` a reader that exits early makes the
# pipeline report the writer's SIGPIPE as a failure. It happens to be safe here — one image, one
# line — but the same shape cost two red runs in ci/docker-smoke.sh, and it is not worth keeping
# a second copy of it around to find out.
SIZES="$(docker images --format '   {{.Repository}}:{{.Tag}}  {{.Size}}' "$IMAGE")"
head -1 <<<"$SIZES"
# The closing line has to tell the truth about what happened: it used to say "passed the gate"
# even when the gate had been skipped.
echo "✓ built $GATED"
