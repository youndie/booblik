#!/usr/bin/env bash
#
# Builds the bundle Maven Central's Portal API takes: every published module of one version, signed,
# in Maven layout, in a single zip.
#
# **One bundle for the whole release, not one push per module.** The Portal validates the bundle as
# a unit, so a release cannot end up half-visible the way a per-module push to a live repository can
# — and a release on Central can never be deleted, only superseded, which is why the failure worth
# preventing is the partial one.
#
# Usage: ci/central-bundle.sh <version>
#
# Signing keys come from Gradle properties, so on a laptop without them this fails loudly instead of
# quietly producing an unsigned bundle the Portal would reject after the upload.

set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: ci/central-bundle.sh <version>}"
STAGING=build/central-staging
BUNDLE="build/central-bundle-$VERSION.zip"

if [ -z "${ORG_GRADLE_PROJECT_SIGNING_KEY:-}" ]; then
    echo "no ORG_GRADLE_PROJECT_SIGNING_KEY: Central rejects unsigned artefacts, so this stops here" >&2
    exit 1
fi

rm -rf "$STAGING" "$BUNDLE"

echo "→ staging $VERSION"
./gradlew --quiet \
    :booblik-protocol:publishAllPublicationsToCentralStagingRepository \
    :booblik-core:publishAllPublicationsToCentralStagingRepository \
    :booblik-client:publishAllPublicationsToCentralStagingRepository \
    :booblik-native:publishAllPublicationsToCentralStagingRepository \
    -PVERSION="$VERSION"

# The Portal builds its own maven-metadata.xml and rejects a bundle carrying one. Gradle writes it
# for a file repository because it cannot know this bundle is not a repository.
find "$STAGING" -name "maven-metadata.xml*" -delete

echo "→ checking every artefact is signed"
MISSING=0
while IFS= read -r artefact; do
    if [ ! -f "$artefact.asc" ]; then
        echo "   unsigned: ${artefact#"$STAGING"/}" >&2
        MISSING=$((MISSING + 1))
    fi
done < <(find "$STAGING" -type f ! -name "*.asc" ! -name "*.md5" ! -name "*.sha1" ! -name "*.sha256" ! -name "*.sha512")
if [ "$MISSING" -gt 0 ]; then
    echo "$MISSING artefact(s) without a signature — the Portal would reject the bundle" >&2
    exit 1
fi

# A version that does not appear where it should is how a bundle uploads green and publishes the
# wrong number. Cheap to ask, and the answer is in the paths.
FOUND=$(find "$STAGING" -type d -name "$VERSION" | wc -l | tr -d ' ')
if [ "$FOUND" -eq 0 ]; then
    echo "nothing staged under a '$VERSION' directory — check -PVERSION reached the build" >&2
    exit 1
fi
echo "   $FOUND module(s) at $VERSION, all signed"

echo "→ zipping"
(cd "$STAGING" && zip -qr "../$(basename "$BUNDLE")" .)
echo "   $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"
