#!/usr/bin/env python3
"""Does recovery get more expensive as the log ages? (M-162, замер 26)

One image, one set of settings, one box, one session, runs alternating: the only thing that differs
between the two variants is whether the data directory holds a hundred hours of log or nothing.
Everything else — pulling the container up, starting a JVM, binding the port — is paid identically
by both, so the *difference* is what age costs even though each number is end-to-end.

Page cache is evicted per file with posix_fadvise(DONTNEED) rather than by dropping the host's
caches: k0s is running on this box, and a global drop would measure the neighbours too. Without the
eviction only the aged variant has anything to cache, so a warm run would flatter exactly the
variant under suspicion.
"""
import json
import os
import shutil
import subprocess
import time

IMAGE = "ghcr.io/youndie/booblik:0.3.1"
AGED = "/mnt/booblik-soak"
# On the same filesystem as the aged log, deliberately: a fresh directory on the root disk
# would compare two devices and call the difference age. Removed after each run so the aged
# variant never sees a stray directory in its data dir.
FRESH = "/mnt/booblik-soak/fresh-probe"
RUNS = 5


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw).stdout


def evict(root):
    """Drop this tree's pages, and only this tree's."""
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            try:
                fd = os.open(path, os.O_RDONLY)
            except OSError:
                continue
            try:
                os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
            finally:
                os.close(fd)


def bytes_in(root):
    total = 0
    for base, _, files in os.walk(root):
        for name in files:
            try:
                total += os.stat(os.path.join(base, name)).st_size
            except OSError:
                pass
    return total


def segments_in(root):
    return sum(1 for base, _, files in os.walk(root) for f in files if f.endswith(".log"))


def start_and_time(data_dir):
    sh("docker", "rm", "-f", "recov-probe")
    started = time.monotonic()
    subprocess.run(
        ["docker", "run", "-d", "--name", "recov-probe", "--restart=no",
         "-v", f"{data_dir}:/var/lib/booblik",
         "-p", "127.0.0.1:9092:9092",
         "-e", "BOOBLIK_TOPICS=soak:2",
         "-e", "BOOBLIK_SEGMENT_CAPACITY_BYTES=16777216",
         "-e", "BOOBLIK_RETENTION_BYTES=268435456",
         "-e", "BOOBLIK_RETENTION_CHECK_MILLIS=30000",
         IMAGE],
        capture_output=True, check=True,
    )
    # A container created fresh each run, so its log cannot carry a "listening" line from before —
    # the trap that made an earlier probe fire before the broker was up.
    deadline = started + 120
    while time.monotonic() < deadline:
        if "booblik listening" in sh("docker", "logs", "recov-probe"):
            elapsed = time.monotonic() - started
            sh("docker", "rm", "-f", "recov-probe")
            return elapsed * 1000
        time.sleep(0.02)
    print(sh("docker", "logs", "recov-probe"))
    sh("docker", "rm", "-f", "recov-probe")
    raise SystemExit("never came up")


sh("docker", "stop", "soak-broker")
results = {"aged": [], "fresh": []}

print(f"aged:  {segments_in(AGED)} segments, {bytes_in(AGED) / 2**20:.0f} MiB on disk")
for run in range(1, RUNS + 1):
    # Alternating rather than five of one then five of the other: anything that drifts during the
    # experiment then lands on both variants instead of on whichever went second.
    for variant, path in (("aged", AGED), ("fresh", FRESH)):
        if variant == "fresh":
            shutil.rmtree(FRESH, ignore_errors=True)
            os.makedirs(FRESH)
            # The image runs as uid 10001. A directory it cannot write is not an error here: the
            # broker printed its whole configuration and then neither served nor exited.
            os.chown(FRESH, 10001, 10001)
        evict(path)
        ms = start_and_time(path)
        results[variant].append(ms)
        print(f"  run {run}  {variant:5} {ms:8.0f} ms")
        if variant == "fresh":
            shutil.rmtree(FRESH, ignore_errors=True)

print()
for variant, values in results.items():
    ordered = sorted(values)
    print(f"{variant:5}  median {ordered[len(ordered) // 2]:7.0f} ms   min {ordered[0]:7.0f}   max {ordered[-1]:7.0f}")
print(json.dumps(results))
shutil.rmtree(FRESH, ignore_errors=True)
sh("docker", "start", "soak-broker")
