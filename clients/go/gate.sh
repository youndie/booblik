#!/usr/bin/env bash
#
# The Go client's own gate, called by ci/gate.sh.
#
# Exits 77 when there is no Go toolchain, which the top-level gate reports as skipped rather than
# counting as green. Anything else non-zero is a real failure.

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v go >/dev/null 2>&1; then
    echo "no go toolchain on this machine"
    exit 77
fi

echo "→ gofmt"
unformatted="$(gofmt -l .)"
if [ -n "$unformatted" ]; then
    echo "   not gofmt-clean:" >&2
    echo "$unformatted" | sed 's/^/     /' >&2
    exit 1
fi

echo "→ go vet"
go vet ./...

# -race, because the accumulator is a goroutine holding state that the caller's goroutines feed —
# exactly the shape whose bugs decline to reproduce on demand.
echo "→ go test -race"
go test -race -count=1 ./...
