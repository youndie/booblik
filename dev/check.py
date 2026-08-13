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
import os
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


def queue() -> int:
    """stdin holds one /stats line per worker of the queue profile.

    Two claims, and the second is the one that would break quietly:

      * every task is won by exactly one worker. Counted against `doneTasks` — the number of
        **distinct** tasks in the replayed log — and not against the sum of the workers' own
        `finished` counters. An earlier version compared won against finished, and those two grow
        together when a task is done twice, so the check would have stayed silent on exactly the
        failure it was written for.
      * all workers agree on how many tasks are done. They replay the same partition and decide by
        the timestamps written into the records, never by their own clocks, so agreement is a
        property of the protocol rather than luck. Disagreement means somebody judged a lease by a
        local now().
    """
    workers = [json.loads(line) for line in sys.stdin if line.strip()]
    won = sum(w["won"] for w in workers)
    finished = sum(w["finished"] for w in workers)
    held = sum(1 for w in workers if w["current"] is not None)
    done_views = {w["doneTasks"] for w in workers}
    attempts = sum(w["attempts"] for w in workers)
    lost = sum(w["lost"] for w in workers)
    failed = False

    for w in workers:
        print(f"   {w['name']}: won {w['won']}, lost {w['lost']}, finished {w['finished']}, "
              f"holding {w['current']}, sees {w['doneTasks']} done of {w['knownTasks']}")

    done = max(done_views)
    if won != done + held:
        print(f"::error:: {won} tasks won but only {done} distinct tasks are done and {held} held "
              f"— a task was won more than once")
        failed = True
    if finished != done:
        print(f"::error:: workers finished {finished} tasks between them but only {done} distinct "
              f"tasks are done — the same task was worked twice")
        failed = True
    if len(done_views) != 1:
        print(f"::error:: workers disagree on how many tasks are done: {sorted(done_views)}")
        failed = True
    if any(w["timeouts"] for w in workers):
        print("::error:: a worker gave up waiting for its own claim to come back round the log")
        failed = True

    if failed:
        return 1
    print(f"   {won} tasks, each won by exactly one worker; all three agree on {done_views.pop()} done")
    print(f"   {lost} of {attempts} attempts lost a race — the cost of having no coordinator (M-103)")
    return 0


def redistributed() -> int:
    """stdin holds one surviving worker's view of the task the killed worker was holding.

    Two things have to be true, and the second is the one that makes the first mean anything:
    the task is finished, and it is no longer attributed to the worker that died. A task that is
    merely free again proves the lease lapsed; a task that is *done* proves somebody picked it up.
    """
    state = json.load(sys.stdin)
    victim = os.environ["VICTIM"]
    task = os.environ["TASK"]

    print(f"   task {task}: done={state['done']}, heldBy={state['heldBy']}")
    if not state["done"]:
        print(f"::error:: task {task} was never finished after {victim} was killed — nobody took it over")
        return 1
    if state["heldBy"] == victim:
        print(f"::error:: task {task} is still attributed to {victim}, which no longer exists")
        return 1
    print(f"   {victim} died holding it; the log handed it on and it was finished")
    return 0


def roundtrip() -> int:
    """stdin holds whatever came out of the far Kafka topic; COUNT says how many went in.

    The set has to be complete. Duplicates are allowed and counted rather than failed: both relays
    move the position only after the far side acknowledged, so a restart repeats a batch by design.
    Anything *extra* that is not one of the inputs would be a different bug and is reported too.
    """
    expected = {f"order-{n}" for n in range(1, int(os.environ["COUNT"]) + 1)}
    seen = [line.strip() for line in sys.stdin if line.strip()]
    got = set(seen)

    missing = expected - got
    unexpected = got - expected
    duplicates = len(seen) - len(got)

    print(f"   {len(seen)} records out, {len(got)} distinct, {duplicates} repeated")
    if missing:
        print(f"::error:: {len(missing)} records never came back, e.g. {sorted(missing)[:5]}")
        return 1
    if unexpected:
        print(f"::error:: {len(unexpected)} records came back that were never sent: {sorted(unexpected)[:5]}")
        return 1
    print(f"   all {len(expected)} made the round trip"
          + (f"; {duplicates} arrived twice, which at-least-once permits" if duplicates else ""))
    return 0


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "split":
        sys.exit(split())
    if mode == "roundtrip":
        sys.exit(roundtrip())
    if mode == "queue":
        sys.exit(queue())
    if mode == "redistributed":
        sys.exit(redistributed())
    sys.exit(resumed(int(sys.argv[2])))
