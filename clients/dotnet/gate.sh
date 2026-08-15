#!/usr/bin/env bash
#
# The .NET client's own gate, called by ci/gate.sh.
#
# Exits 77 when there is no .NET SDK, which the top-level gate reports as skipped rather than
# counting as green. Anything else non-zero is a real failure.
#
# Unlike the Go, Python and Node clients this one has test dependencies — xunit and the test SDK —
# because .NET has no test runner in the box and hand-rolling one would be a worse trade than a
# restore. The library itself still references nothing.

set -euo pipefail
cd "$(dirname "$0")"

if ! command -v dotnet >/dev/null 2>&1; then
    echo "no dotnet on this machine"
    exit 77
fi

# Warnings are errors in both projects, so this is the format and analysis check as well as the
# compile: there is no separate linter step to drift out of sync with it.
echo "→ dotnet test"
dotnet test Booblik.Tests/Booblik.Tests.csproj --nologo -v quiet
