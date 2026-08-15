#!/usr/bin/env bash
#
# The Python client's own gate, called by ci/gate.sh.
#
# Exits 77 when there is no Python, which the top-level gate reports as skipped rather than counting
# as green. Anything else non-zero is a real failure.
#
# No linter and no formatter, and that is deliberate rather than an omission: this client has **no
# dependencies at all**, tests included — `unittest` is the standard library — and adding ruff or
# black would make `pip install` a precondition for checking a package that otherwise needs nothing.
# The rest of the repository's Python (the conformance harness, the vector generator) is held to the
# same rule.

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v python3 >/dev/null 2>&1; then
    echo "no python3 on this machine"
    exit 77
fi

echo "→ python -m unittest"
python3 -m unittest discover
