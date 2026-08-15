#!/usr/bin/env bash
#
# The Java client's own gate, called by ci/gate.sh.
#
# Exits 77 when there is no JDK, which the top-level gate reports as skipped rather than counting as
# green. Anything else non-zero is a real failure.
#
# No separate linter: `-Xlint:all -Werror` is on for every compilation, so the build **is** the
# style and analysis check and cannot drift out of sync with it. The library itself has no
# dependencies; only the tests need JUnit, the JDK having no test runner in the box.

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v java >/dev/null 2>&1; then
    echo "no java on this machine"
    exit 77
fi

echo "→ gradle test"
./gradlew --quiet test
