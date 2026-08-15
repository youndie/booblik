#!/usr/bin/env bash
#
# The Java client under test, for conformance/harness. Builds the runnable jar if it must, then runs
# it.
#
# The harness starts one process per verb, so this rebuilds only when a source file is newer than
# the jar — otherwise every check would pay for a Gradle invocation.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/build/libs/booblik-java-0.1.0-conformance.jar"

if [ ! -f "$JAR" ] || [ -n "$(find "$DIR/src/main" -name '*.java' -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    (cd "$DIR" && ./gradlew --quiet conformanceJar)
fi

exec java -jar "$JAR" "$@"
