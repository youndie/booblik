#!/usr/bin/env bash
#
# The Go client under test, for conformance/harness. Compiles if it must, then becomes the binary.
#
# The harness starts one process per verb, so this recompiles only when a source file is newer than
# the binary — otherwise every single check would pay for a compile.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
BINARY="$DIR/build/conformance"

if [ ! -x "$BINARY" ] || [ -n "$(find "$DIR" -name '*.go' -newer "$BINARY" -print -quit)" ]; then
    mkdir -p "$DIR/build"
    (cd "$DIR" && go build -o "$BINARY" ./cmd/conformance)
fi

exec "$BINARY" "$@"
