#!/usr/bin/env bash
#
# Runs a Gradle task on a Linux machine (WSL) against the **working tree**, with no commit.
#
# Why a project with no Linux targets needs this: **measurements**. Development happens on macOS
# (Apple M1, APFS, 8 cores) and brokers live on Linux. Risk 4 of the research is exactly about that,
# and some of this project's conclusions are properties of the filesystem rather than of the code:
# the ratio between `msync` and `fsync` (§1.9) may well come out different on ext4, and here is the
# only place to check.
#
# How this works, and why it is not obvious
# -----------------------------------------
# SSH lands on **Windows**, not in Ubuntu: WSL has no listener of its own. Commands get in through
# `wsl -d <distro> --`, which is why neither `scp` nor a plain `rsync` works — both put the files in
# the Windows filesystem. `--rsync-path` is the way out: it says what to run as rsync on the far
# side, and then the destination path is read by the Linux rsync.
#
# `--delete` is required. Syncing through `tar` — the obvious approach — does **not** remove deleted
# files, and that presents as "the edit had no effect": the old file keeps building alongside the
# new one.
#
# `build/`, `.gradle/` and `.git/` stay behind: the build directory is platform-bound and the
# history over there is its own.
#
# On the JDK: Ubuntu has 21 and the project needs 25 (FFM mapping, §1.5). Installing it by hand is
# not necessary — `settings.gradle.kts` enables the foojay resolver and Gradle fetches the toolchain
# itself. That is what makes the first run longer.
#
# Usage:
#   ./ci/wsl-run.sh                      # ./gradlew check
#   ./ci/wsl-run.sh :booblik-benchmark:mainBenchmark
#   BOOBLIK_WSL_RAW=1 ./ci/wsl-run.sh :booblik-benchmark:probeDurability
#
# Settings come from the environment:
#   BOOBLIK_WSL_HOST    user@address of the machine running WSL (required)
#   BOOBLIK_WSL_DISTRO  distribution name, Ubuntu-24.04 by default
#   BOOBLIK_WSL_PATH    path to the checkout inside WSL, booblik by default
#   BOOBLIK_WSL_RAW     non-empty prints the full output (needed for measurements)

set -euo pipefail

# The address comes from the environment and is not kept in the repository: this is somebody else's
# private infrastructure, not a setting of this project.
HOST="${BOOBLIK_WSL_HOST:?set BOOBLIK_WSL_HOST=user@address}"
DISTRO="${BOOBLIK_WSL_DISTRO:-Ubuntu-24.04}"

# The path **must** be an absolute Linux one, and this is the central trap of the whole exercise. A
# session inside WSL starts in `/mnt/c/Users/<somebody>` — the Windows filesystem, mounted over 9p.
# A relative destination lands exactly there, everything builds and runs, and the only way to notice
# is by the numbers: for a project that measures disks, 9p on NTFS is not "a bit slower", it is a
# different subject of measurement. So the home directory is asked of WSL itself rather than
# guessed.
if [ -z "${BOOBLIK_WSL_PATH:-}" ]; then
    WSL_HOME="$(ssh -o ConnectTimeout=20 "$HOST" "wsl -d $DISTRO -- bash -lc 'echo \$HOME'" | tr -d '\r')"
    [ -n "$WSL_HOME" ] || { echo "could not read \$HOME inside WSL" >&2; exit 1; }
    REMOTE="$WSL_HOME/booblik"
else
    REMOTE="$BOOBLIK_WSL_PATH"
fi
case "$REMOTE" in
    /mnt/*) echo "path $REMOTE is in the Windows filesystem — measurements from there are void" >&2; exit 1 ;;
    /*) ;;
    *) echo "path $REMOTE has to be absolute" >&2; exit 1 ;;
esac

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then TASKS=("check"); fi
# Every argument is quoted separately: the remote bash parses what we substitute a second time, and
# without this `-Pargs="MAPPED 60"` arrives there as two Gradle arguments. It surfaces as
# "task 60 not found" — which looks like a typo in the call rather than a broken transport.
TASKS_QUOTED="$(printf ' %q' "${TASKS[@]}")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ syncing the working tree to $HOST:$REMOTE"
rsync -az --delete \
    --exclude 'build/' \
    --exclude '.gradle/' \
    --exclude '.git/' \
    --exclude '.kotlin/' \
    --exclude '.DS_Store' \
    --rsync-path="wsl -d $DISTRO -- rsync" \
    "$ROOT/" "$HOST:$REMOTE/"

echo "→ ${TASKS[*]}"

ssh -o ConnectTimeout=20 "$HOST" "wsl -d $DISTRO -- bash -s" <<REMOTE_SCRIPT
set -euo pipefail
# Nothing may ask for a password: there is no interactive input on the far side, and a prompt hangs
# the session for good instead of failing with something readable.
export GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/true
cd "$REMOTE"
# The flag is substituted here rather than read there: the environment does not travel over ssh, and
# a variable left for the remote bash to expand is always empty — so the full output never turned
# on.
if [ -n "${BOOBLIK_WSL_RAW:-}" ]; then
    ./gradlew$TASKS_QUOTED --console=plain 2>&1
else
    ./gradlew$TASKS_QUOTED --console=plain 2>&1 | grep -E '^e: |FAILED|tests? completed|BUILD' | tail -20
fi
REMOTE_SCRIPT
