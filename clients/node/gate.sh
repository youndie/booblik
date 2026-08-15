#!/usr/bin/env bash
#
# The Node client's own gate, called by ci/gate.sh.
#
# Exits 77 when there is no Node, which the top-level gate reports as skipped rather than counting
# as green. Anything else non-zero is a real failure.
#
# No linter and no formatter, and that is deliberate: this client has **no dependencies at all**,
# tests included — `node:test` and `node:assert` are the standard library — and adding eslint or
# prettier would make `npm install` a precondition for checking a package that otherwise needs
# nothing. The same rule the Python client is held to.

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
    echo "no node on this machine"
    exit 77
fi

echo "→ node --test"
node --test
