#!/usr/bin/env bash
#
# The .NET client under test, for conformance/harness.
#
# The harness starts one process per verb, so this builds only when a source file is newer than the
# assembly — otherwise every check would pay for a compile. `dotnet <dll>` rather than `dotnet run`
# for the same reason: `run` re-evaluates the project every single time.

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ASSEMBLY="$DIR/Booblik.Conformance/bin/Release/net8.0/Booblik.Conformance.dll"

if [ ! -f "$ASSEMBLY" ] || [ -n "$(find "$DIR/Booblik" "$DIR/Booblik.Conformance" -name '*.cs' -newer "$ASSEMBLY" -print -quit)" ]; then
    dotnet build "$DIR/Booblik.Conformance/Booblik.Conformance.csproj" -c Release --nologo -v quiet >/dev/null
fi

exec dotnet "$ASSEMBLY" "$@"
