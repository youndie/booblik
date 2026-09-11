#!/usr/bin/env python3
"""A third point on the same curve: half the log, made by retention rather than by me.

Two points say the aged log costs more than an empty one; they cannot say *what* it costs more
about. If the cost tracks the number of segments, then it is bounded by the retention budget and
a broker that has run for a hundred hours recovers no slower than one that has run for three —
which is exactly the claim M-162 puts up to be disproved.

The original log is copied rather than pruned: the copy is handed to a broker with half the
retention budget and **retention** does the deleting, which is both non-destructive and more
faithful than me choosing files.
"""
import os, shutil, subprocess, time, json

IMAGE = "ghcr.io/youndie/booblik:0.3.1"
HALF = "/mnt/booblik-soak/half-probe"
AGED = "/mnt/booblik-soak"
RUNS = 5

def sh(*a): return subprocess.run(a, capture_output=True, text=True).stdout

def evict(root):
    for base, _, files in os.walk(root):
        for name in files:
            try: fd = os.open(os.path.join(base, name), os.O_RDONLY)
            except OSError: continue
            try: os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
            finally: os.close(fd)

def segments(root):
    return sum(1 for b, _, fs in os.walk(root) for f in fs if f.endswith(".log"))

def run(data_dir, retention, wait_for_prune=0):
    sh("docker", "rm", "-f", "recov-probe")
    started = time.monotonic()
    subprocess.run(["docker","run","-d","--name","recov-probe","--restart=no",
        "-v", f"{data_dir}:/var/lib/booblik", "-p","127.0.0.1:9092:9092",
        "-e","BOOBLIK_TOPICS=soak:2","-e","BOOBLIK_SEGMENT_CAPACITY_BYTES=16777216",
        "-e",f"BOOBLIK_RETENTION_BYTES={retention}","-e","BOOBLIK_RETENTION_CHECK_MILLIS=5000",
        IMAGE], capture_output=True, check=True)
    ms = None
    deadline = started + 120
    while time.monotonic() < deadline:
        if "booblik listening" in sh("docker","logs","recov-probe"):
            ms = (time.monotonic() - started) * 1000
            break
        time.sleep(0.02)
    if wait_for_prune:
        time.sleep(wait_for_prune)
    sh("docker","rm","-f","recov-probe")
    return ms

sh("docker", "stop", "soak-broker")
shutil.rmtree(HALF, ignore_errors=True)
print("copying the aged log (the original is not touched)")
os.makedirs(HALF)
for topic in ("soak-0", "soak-1"):
    shutil.copytree(os.path.join(AGED, topic), os.path.join(HALF, topic))
for base, dirs, files in os.walk(HALF):
    os.chown(base, 10001, 10001)
    for f in files: os.chown(os.path.join(base, f), 10001, 10001)
print(f"  copy has {segments(HALF)} segments")

print("letting retention halve it")
run(HALF, 134217728, wait_for_prune=25)
print(f"  copy now has {segments(HALF)} segments")

values = []
for i in range(1, RUNS + 1):
    evict(HALF)
    ms = run(HALF, 134217728)
    values.append(ms)
    print(f"  run {i}  half  {ms:8.0f} ms")

ordered = sorted(values)
print(f"half   median {ordered[len(ordered)//2]:7.0f} ms   min {ordered[0]:7.0f}   max {ordered[-1]:7.0f}")
print(json.dumps({"segments": segments(HALF), "half": values}))
shutil.rmtree(HALF, ignore_errors=True)
sh("docker", "start", "soak-broker")
