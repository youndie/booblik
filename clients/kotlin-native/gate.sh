#!/usr/bin/env bash
#
# The Kotlin/Native client's own gate, called by ci/gate.sh.
#
# Runs the native tests of the shared protocol and of the client, on whichever target this host can
# actually execute. That last part is the whole reason this is a script and not a line in
# `./gradlew check`: a linuxX64 test binary does not run on macOS and a macosArm64 one does not run
# on Linux, so "the native code is tested" means a different task on each machine.
#
# Exits 77 when there is no Kotlin/Native target for this host, which the top-level gate reports as
# skipped rather than counting as green.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) TARGET=macosArm64 ;;
    Linux-x86_64) TARGET=linuxX64 ;;
    *)
        echo "no runnable Kotlin/Native target for $(uname -s)-$(uname -m)"
        exit 77
        ;;
esac

echo "→ native tests on $TARGET"
cd "$ROOT"
./gradlew ":booblik-protocol:${TARGET}Test" ":booblik-native:${TARGET}Test"
