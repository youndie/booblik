#!/usr/bin/env bash
#
# Checks the **image**, not the build.
#
# `ci/smoke.sh` checks the distribution: that it starts, serves and survives a restart. One more
# class of failures lives between the distribution and the image, and neither of those checks sees
# it: the wrong user and no rights on the volume, configuration that never arrived from the
# environment, a health check pointing at a path that does not exist, and above all a runtime
# profile overridden by `JAVA_OPTS` — after which what ships is a process nobody measured (risk 7).
#
# Usage:
#   ./ci/docker-smoke.sh              # builds booblik:smoke and checks it
#   BOOBLIK_IMAGE=booblik:1.0 ./ci/docker-smoke.sh   # checks an existing image, builds nothing

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${BOOBLIK_IMAGE:-booblik:smoke}"
NAME="booblik-smoke-$$"
PORT="${BOOBLIK_SMOKE_PORT:-19099}"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

logs() {
    docker logs "$NAME" 2>&1
}

# Every assertion about the log waits for its line instead of reading once, and every one of them
# reads the log into a **variable** before matching. Both halves were paid for.
#
# `docker logs` is not synchronous with the container's stdout, so reading once and asserting races:
# a run reported BOOBLIK_TOPICS as not applied and printed the log with the line in it one command
# later. Hence the waiting.
#
# And piping `docker logs` into `grep -q` or `grep -m1` is a trap under `set -o pipefail`: grep
# exits at the first match, `docker logs` takes SIGPIPE, and the pipeline reports 141 — **a failure
# on a successful match**. That cost two red runs, and in between I proposed this exact mechanism
# and talked myself out of it because an interactive shell happened not to reproduce it. A here-
# string has no pipe and no writer to kill.
await_log() {
    local pattern="$1" tries="${2:-60}" out
    for _ in $(seq 1 "$tries"); do
        out="$(logs)"
        grep -q "$pattern" <<<"$out" && return 0
        sleep 0.5
    done
    return 1
}

if [ -z "${BOOBLIK_IMAGE:-}" ]; then
    echo "→ building image $IMAGE"
    docker build -t "$IMAGE" "$ROOT" >/dev/null
fi

echo "→ starting"
docker run -d --name "$NAME" -p "$PORT:9092" -e BOOBLIK_TOPICS=smoke:2 "$IMAGE" >/dev/null
await_log "booblik listening" || {
    echo "the broker in the container did not come up:"; logs | tail -20; exit 1
}

echo "→ configuration arrived from the environment"
await_log "topics=smoke:2" 20 || {
    echo "BOOBLIK_TOPICS was not applied:"; logs | head -10; exit 1
}
echo "   topics=smoke:2 from BOOBLIK_TOPICS"

# M-84. The profile is baked into the start script, but one `JAVA_OPTS` line in somebody else's
# `Dockerfile` overrides it, and from the outside there was no way to notice. The broker prints its
# own arguments at startup for exactly this check.
echo "→ the runtime profile reached the process"
await_log "jvm:" 20 || { echo "the broker printed no jvm: line"; logs | head -10; exit 1; }
JVM_LINE="$(grep -m1 'jvm:' <<<"$(logs)")"
for flag in -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M \
            -Xss256k -XX:MaxMetaspaceSize=80M -Xmx64M; do
    case "$JVM_LINE" in
        *"$flag"*) ;;
        *) echo "flag $flag is missing; the process runs under a different profile than the one measured"
           echo "  actual: $JVM_LINE"; exit 1 ;;
    esac
done
echo "   all six flags present"

echo "→ the health check answers over METADATA"
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9092
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9999 >/dev/null 2>&1 && {
    echo "the health check called a port with nobody on it healthy"; exit 1
}
echo "   and tells a live port from a dead one"

echo "→ the process is not root"
WHO="$(docker exec "$NAME" id -un)"
[ "$WHO" = "booblik" ] || { echo "the process runs as $WHO, expected booblik"; exit 1; }
echo "   user $WHO"

echo "→ data lands on the volume, and the segment is sparse"
docker exec "$NAME" /opt/booblik/bin/booblik-health 127.0.0.1 9092 >/dev/null
SEG="$(docker exec "$NAME" sh -c 'ls /var/lib/booblik/smoke-0/*.log 2>/dev/null | head -1' || true)"
if [ -n "$SEG" ]; then
    read -r APPARENT BLOCKS <<<"$(docker exec "$NAME" stat -c '%s %b' "$SEG")"
    echo "   segment: $APPARENT B apparent, $((BLOCKS / 2)) KiB occupied"
    [ "$BLOCKS" -lt 2048 ] || { echo "the segment materialised in full — sparseness is gone"; exit 1; }
fi

echo "✓ the image starts as the right user, under the right profile, and answers METADATA"
