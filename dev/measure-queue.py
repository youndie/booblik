"""Turns two snapshots of the claims log into the numbers M-103 asks for.

stdin is one worker's /stats; the counts come in through the environment because they are
differences between two readings of the log, not something a single reading knows.
"""

import json
import os
import sys

won = int(os.environ["WINS1"]) - int(os.environ["WINS0"])
attempts = int(os.environ["ATTEMPTS1"]) - int(os.environ["ATTEMPTS0"])
window = int(os.environ["WINDOW"])
latency = json.load(sys.stdin)["claimLatencyMicros"]

# The instrument checks itself. A claim that wins is one of the claims that were made, so
# wins above attempts is not a surprising result — it is a broken reading, and the first version of
# this script printed one rather than stopping.
if won > attempts:
    print(f"::error:: {won} wins out of {attempts} attempts is impossible — the counts are misread")
    raise SystemExit(1)

lost = attempts - won
share = 100.0 * lost / attempts if attempts else 0.0
per_task = attempts / won if won else 0.0

print()
print(f"  workers            {os.environ['WORKERS']}, pick={os.environ['PICK']}")
print(f"  tasks won          {won} in {window}s = {won / window:.1f}/s")
print(f"  claim attempts     {attempts} = {per_task:.2f} per task")
print(f"  attempts lost      {lost} ({share:.1f} %)")
print(f"  claim round trip   p50 {latency['p50'] / 1000:.2f} ms, "
      f"p90 {latency['p90'] / 1000:.2f} ms, p99 {latency['p99'] / 1000:.2f} ms")
