#!/usr/bin/env bash
#
# The Node client under test, for conformance/harness. Nothing to build.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

exec node "$DIR/bin/conformance.js" "$@"
