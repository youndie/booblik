#!/usr/bin/env bash
#
# The asyncio client's own gate, called by ci/gate.sh.
#
# Only the asyncio tests: the synchronous ones belong to `clients/python` and running them twice
# would make a red run ambiguous about which client broke.
#
# Exits 77 when there is no Python, which the top-level gate reports as skipped rather than counting
# as green.

set -euo pipefail
cd "$(dirname "$0")/../python"

if ! command -v python3 >/dev/null 2>&1; then
    echo "no python3 on this machine"
    exit 77
fi

echo "→ python -m unittest tests.test_aio"
python3 -m unittest tests.test_aio
