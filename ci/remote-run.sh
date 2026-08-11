#!/usr/bin/env bash
#
# Runs a Gradle task on a rented Linux machine against the **working tree**, with no commit.
#
# What separates this from `wsl-run.sh` is the subject, not the transport. That script ships the
# tree into WSL, and everything that comes back is a WSL2 number: ext4 on a VHDX on NTFS, a
# Microsoft kernel, writeback decided by Hyper-V (§1.12). Relative comparisons survive that;
# absolute ones do not, and about durability WSL2 says nothing at all. Here the machine is real,
# which is why milestone M-46 exists.
#
# This one has a trap of its own. For WSL it was a path under `/mnt/c`; here it is a **network
# disk**. A rented server with a mounted Volume looks and behaves like an ordinary server,
# everything builds and runs, and what gets measured is the provider's network. So the script asks
# the machine what the run directory sits on and refuses nfs/cifs/9p; a spinning disk is a warning
# rather than a refusal.
#
# Usage:
#   BOOBLIK_REMOTE_HOST=root@address ./ci/remote-run.sh                 # ./gradlew check
#   BOOBLIK_REMOTE_HOST=root@address ./ci/remote-run.sh :booblik-benchmark:mainBenchmark
#   BOOBLIK_REMOTE_RAW=1 ... ./ci/remote-run.sh :booblik-benchmark:probeDurability
#
# Settings come from the environment:
#   BOOBLIK_REMOTE_HOST  user@address (required)
#   BOOBLIK_REMOTE_PATH  path to the checkout, ~/booblik on the far side by default
#   BOOBLIK_REMOTE_KEY   private key; ssh picks one by default
#   BOOBLIK_REMOTE_RAW   non-empty prints the full output (needed for measurements)

set -euo pipefail

# The address and the key come from the environment and are not kept in the repository: this is
# somebody else's private infrastructure, not a setting of this project.
HOST="${BOOBLIK_REMOTE_HOST:?set BOOBLIK_REMOTE_HOST=user@address}"

SSH_OPTS=(-o ConnectTimeout=20 -o BatchMode=yes)
[ -n "${BOOBLIK_REMOTE_KEY:-}" ] && SSH_OPTS+=(-i "$BOOBLIK_REMOTE_KEY")

REMOTE="${BOOBLIK_REMOTE_PATH:-}"
if [ -z "$REMOTE" ]; then
    REMOTE="$(ssh "${SSH_OPTS[@]}" "$HOST" 'echo $HOME/booblik' | tr -d '\r')"
fi
case "$REMOTE" in
    /*) ;;
    *) echo "path $REMOTE has to be absolute" >&2; exit 1 ;;
esac

# The storage check runs before the sync: no point shipping the tree somewhere there is nothing to
# measure. It looks at the nearest existing ancestor — the directory itself may not be there yet.
read -r FSTYPE SOURCE ROTA <<EOF
$(ssh "${SSH_OPTS[@]}" "$HOST" "bash -s" <<REMOTE_PROBE
set -eu
p="$REMOTE"
while [ ! -e "\$p" ]; do p="\$(dirname "\$p")"; done
fs="\$(findmnt -no FSTYPE -T "\$p")"
src="\$(findmnt -no SOURCE -T "\$p")"
pk="\$(lsblk -no PKNAME "\$src" 2>/dev/null | head -1 || true)"
rota="\$(cat "/sys/block/\$pk/queue/rotational" 2>/dev/null || echo '?')"
echo "\$fs \$src \$rota"
REMOTE_PROBE
)
EOF

case "$FSTYPE" in
    nfs|nfs4|cifs|smb3|9p|virtiofs)
        echo "the run directory sits on $FSTYPE ($SOURCE) — that is network storage," >&2
        echo "and disk measurements from there describe the provider's network, not a device" >&2
        exit 1 ;;
esac
[ "$ROTA" = "1" ] && echo "! disk $SOURCE is rotational — these numbers cannot be compared with NVMe runs" >&2

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then TASKS=("check"); fi
# Every argument is quoted separately: the remote bash parses what we substitute a second time, and
# without this `-Pargs="PRODUCE SELECTOR ZERO_COPY"` arrives there as three Gradle arguments. It
# surfaces not as an argument error but as "task SELECTOR not found" — which looks like a typo in
# the call rather than a broken transport.
TASKS_QUOTED="$(printf ' %q' "${TASKS[@]}")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "→ syncing the working tree to $HOST:$REMOTE (${FSTYPE} on ${SOURCE})"
# `--delete` is required: syncing through tar does not remove deleted files, and that presents as
# "the edit had no effect" — the old file keeps building alongside the new one.
rsync -az --delete \
    --exclude 'build/' \
    --exclude '.gradle/' \
    --exclude '.git/' \
    --exclude '.kotlin/' \
    --exclude '.DS_Store' \
    -e "ssh ${SSH_OPTS[*]}" \
    "$ROOT/" "$HOST:$REMOTE/"

echo "→ ${TASKS[*]}"

# No JDK needs installing on the far side: `settings.gradle.kts` enables the foojay resolver and
# Gradle fetches toolchain 25 itself. That is what makes the first run longer.
ssh "${SSH_OPTS[@]}" "$HOST" "bash -s" <<REMOTE_SCRIPT
set -euo pipefail
# Nothing may ask for a password: there is no interactive input on the far side, and a prompt hangs
# the session for good instead of failing with something readable.
export GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/true
cd "$REMOTE"
# The flag is substituted here rather than read there: the environment does not travel over ssh, and
# a variable left for the remote bash to expand is always empty.
if [ -n "${BOOBLIK_REMOTE_RAW:-}" ]; then
    ./gradlew$TASKS_QUOTED --console=plain 2>&1
else
    ./gradlew$TASKS_QUOTED --console=plain 2>&1 | grep -E '^e: |FAILED|tests? completed|BUILD' | tail -20
fi
REMOTE_SCRIPT
