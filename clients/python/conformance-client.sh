#!/usr/bin/env bash
#
# The Python client under test, for conformance/harness. Nothing to build.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

# PYTHONPATH rather than an install: the harness has to check the working tree, not whatever version
# happens to be installed in some environment.
PYTHONPATH="$DIR${PYTHONPATH:+:$PYTHONPATH}" exec python3 "$DIR/conformance.py" "$@"
