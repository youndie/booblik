#!/usr/bin/env bash
#
# Brings up the booblik soak: a broker on a volume of its own, a producer and a consumer on the
# published Python client, and a sampler.
#
# **The volume is a fixed-size loop image, and that is the load-bearing decision.** This box runs
# k0s beside the stand, so a broker writing for a hundred hours must be physically unable to reach
# the host's free space. Two gibibytes, allocated up front rather than sparse, so the cost is paid
# and visible now instead of arriving as a surprise on day three.

set -euo pipefail

ROOT=/opt/booblik-soak
MOUNT=/mnt/booblik-soak
IMAGE=$ROOT/volume.img
BROKER_IMAGE=ghcr.io/youndie/booblik:0.3.0

VOLUME_MB=${VOLUME_MB:-2048}
# Well under the volume, and per **partition** — `retainedBytesPerPartition`, not a total. Two
# partitions at 256 MiB is 512 MiB of budget inside 2 GiB.
RETENTION_BYTES=${RETENTION_BYTES:-268435456}
# Much smaller than the budget on purpose: retention removes whole segments and never the active
# one (`live.size > 1`), so a segment as large as the budget means nothing is ever deleted and the
# volume grows until it is full. That is the trap this sizing exists to avoid.
SEGMENT_BYTES=${SEGMENT_BYTES:-16777216}

mkdir -p "$ROOT/state"

if ! mountpoint -q "$MOUNT"; then
    echo "→ volume: ${VOLUME_MB} MiB at $IMAGE"
    mkdir -p "$MOUNT"
    [ -f "$IMAGE" ] || { fallocate -l "${VOLUME_MB}M" "$IMAGE"; mkfs.ext4 -q -F "$IMAGE"; }
    mount -o loop "$IMAGE" "$MOUNT"
    chmod 777 "$MOUNT"
fi
df -h "$MOUNT" | tail -1

echo "→ broker"
docker rm -f soak-broker >/dev/null 2>&1 || true
docker run -d --name soak-broker \
    --restart=no \
    -v "$MOUNT:/var/lib/booblik" \
    -p 127.0.0.1:9092:9092 \
    -e BOOBLIK_TOPICS=soak:2 \
    -e BOOBLIK_SEGMENT_CAPACITY_BYTES="$SEGMENT_BYTES" \
    -e BOOBLIK_RETENTION_BYTES="$RETENTION_BYTES" \
    -e BOOBLIK_RETENTION_CHECK_MILLIS=30000 \
    "$BROKER_IMAGE" >/dev/null

for _ in $(seq 1 60); do
    docker logs soak-broker 2>&1 | grep -q "booblik listening" && break
    sleep 1
done
docker logs soak-broker 2>&1 | grep -E "retention:|segment:|listening" | head -5

echo "→ load, on the published client"
docker rm -f soak-load >/dev/null 2>&1 || true
docker run -d --name soak-load \
    --restart=unless-stopped \
    --network host \
    -v "$ROOT/soak.py:/soak.py:ro" \
    -v "$ROOT/state:/state" \
    -e BOOBLIK_BROKER=127.0.0.1:9092 \
    -e SOAK_RATE=50 \
    -e SOAK_RECORD_BYTES=1024 \
    python:3.12-slim \
    sh -c "pip install --quiet --no-cache-dir booblik==0.1.0 && python -u /soak.py" >/dev/null

echo "→ sampler"
pkill -f "$ROOT/sample.sh" >/dev/null 2>&1 || true
nohup "$ROOT/sample.sh" > "$ROOT/sampler.log" 2>&1 &
echo "   pid $!"

echo "✓ up. Ends after 100 hours; the volume is capped at ${VOLUME_MB} MiB and cannot touch the host."
