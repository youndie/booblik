"""Asserts what the sample claims, from the outside, over the same HTTP an operator would use.

Two modes:

  split    stdin holds the publisher's /stats followed by one line per consumer. Every consumer's
           position must equal the number of records written to its partition — smaller means a
           replay, larger means a skip.

  resumed  stdin holds one consumer's /stats after a restart, and argv[2] is where it had got to
           before. The failure being looked for is starting over from the beginning of the log.

           Resuming a little **behind** the last position seen is not a failure: the guarantee is
           at-least-once, and a stop between handling a batch and saving its offset legitimately
           replays that batch. An earlier version of this check demanded that the resume point be
           at or ahead of the last reading, which would have failed on correct behaviour — so the
           distance is reported and only a resume from zero is treated as broken.
"""

import json
import sys


def split() -> int:
    lines = [json.loads(line) for line in sys.stdin if line.strip()]
    publisher, consumers = lines[0], lines[1:]
    failed = False

    for consumer in consumers:
        sent = publisher["perPartition"].get(str(consumer["partition"]), 0)
        position = consumer["position"]
        ok = sent == position
        failed |= not ok
        suffix = "" if ok else "   <-- MISMATCH"
        print(
            f"   partition {consumer['partition']}: {sent} written, "
            f"{consumer['name']} at {position}, lag {consumer['lag']}{suffix}"
        )

    if failed:
        print("::error:: a consumer position does not match what was written to its partition")
        return 1
    print(f"   {publisher['sent']} records, none lost and none handled twice")
    return 0


def resumed(before: int) -> int:
    stats = json.load(sys.stdin)
    at = stats["resumedFrom"]

    if at is None or at == 0:
        print("::error:: the consumer started from the beginning — the position did not survive the restart")
        return 1

    behind = before - at
    replayed = f", replaying {behind}" if behind > 0 else ""
    print(f"   stopped at {before}, resumed from {at}{replayed}, now at {stats['position']}")
    return 0


if __name__ == "__main__":
    mode = sys.argv[1]
    sys.exit(split() if mode == "split" else resumed(int(sys.argv[2])))
