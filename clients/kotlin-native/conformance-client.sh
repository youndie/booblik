#!/usr/bin/env bash
#
# The Kotlin/Native client under test, for conformance/harness.
#
# The sources are not here. They are `:booblik-native` in the main Gradle build, because this client
# is a **target of the shared protocol** rather than a reimplementation — see the README beside this
# script. What lives here is the two files `ci/gate.sh` looks for.
#
# The harness starts one process per verb, so this links only when the binary is missing or older
# than a source file; otherwise every check would pay for a Gradle invocation.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# The Gradle task name capitalises the target, and it is spelled out rather than derived: `${VAR^}`
# is a bash 4 expansion and macOS ships bash 3.2, so deriving it failed with "bad substitution" —
# and only on the path that rebuilds, which is the path that runs when something has changed.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        TARGET=macosArm64
        LINK_TASK=":booblik-native-conformance:linkDebugExecutableMacosArm64"
        ;;
    Linux-x86_64)
        TARGET=linuxX64
        LINK_TASK=":booblik-native-conformance:linkDebugExecutableLinuxX64"
        ;;
    *)
        echo "no Kotlin/Native target for $(uname -s)-$(uname -m)" >&2
        exit 1
        ;;
esac

BINARY="$ROOT/booblik-native-conformance/build/bin/$TARGET/debugExecutable/conformance.kexe"
SOURCES="$ROOT/booblik-native $ROOT/booblik-native-conformance $ROOT/booblik-protocol"

# shellcheck disable=SC2086
if [ ! -x "$BINARY" ] || [ -n "$(find $SOURCES -name '*.kt' -newer "$BINARY" -print -quit 2>/dev/null)" ]; then
    (cd "$ROOT" && ./gradlew --quiet "$LINK_TASK" >/dev/null)
fi

exec "$BINARY" "$@"
