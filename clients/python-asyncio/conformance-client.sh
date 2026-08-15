#!/usr/bin/env bash
#
# The asyncio Python client under test, for conformance/harness.
#
# The sources are not here: they are `booblik/aio.py` in `clients/python`, because this is another
# API over the same package rather than another package. What lives here is the two files
# `ci/gate.sh` looks for — the same arrangement `clients/kotlin-native` uses.

set -euo pipefail
PYTHON="$(cd "$(dirname "$0")/../python" && pwd)"

PYTHONPATH="$PYTHON${PYTHONPATH:+:$PYTHONPATH}" exec python3 "$PYTHON/conformance_aio.py" "$@"
