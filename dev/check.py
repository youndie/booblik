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
    """stdin holds the publisher's /stats followed by one line per consumer.

    Equality is the assertion, and it is only legitimate because `check.sh` pauses the publisher and
    waits for every consumer to reach lag zero before reading anything. Without that this compared
    two numbers that were both moving — issue #12, where the job failed by exactly one record on
    branches that do not touch the sample, and passed on a re-run of the same commit.

    The third time this family of check has had to learn that a distributed reading taken at two
    instants is not a disagreement. The other two were the queue's, in `queue()` below.
    """
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
        print(
            "::error:: a consumer position does not match what was written to its partition. "
            "The publisher was paused and every consumer had reached lag zero before these numbers "
            "were read, so this is a real disagreement rather than a race"
        )
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

        Stated as a **bound rather than an equality**, and that is the second time this check has
        had to learn the same lesson. A worker writes its DONE record, and only then clears
        `current` and increments `finished`; a snapshot taken inside that window sees the log
        already ahead of the worker's own counters. `won == done + held` then fails on a perfectly
        correct run — 98 won, 98 done, 1 held — and accuses the sample of doing a task twice.
        What the log actually promises is that a won task is either done or still held, which is
        `won <= done + held`, and that nothing is done that was not won, which is `done <= won`.
        Between them they still catch a task won twice: that is the only way `won` can exceed the
        tasks the log accounts for.
      * workers do not **disagree** about what the log says. The protocol promises the same verdict
        on the same prefix — decided by the timestamps written into the records, never by a local
        clock — and says nothing about all workers standing at the same offset at the same instant,
        which they cannot. So the assertion is monotonic rather than equal: a worker that has
        replayed further must see at least as many tasks done. An earlier version demanded exact
        agreement and failed on 99 against 100, which was one worker being a single record behind.
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
              f"holding {w['current']}, sees {w['doneTasks']} done of {w['knownTasks']} "
              f"(replayed to {w['consumedUpTo']})")

    done = max(done_views)
    if won > done + held:
        print(f"::error:: {won} tasks won but the log accounts for {done} done and {held} held "
              f"— a task was won more than once")
        failed = True
    if done > won:
        print(f"::error:: the log has {done} tasks done but workers won {won} "
              f"— something completed a task nobody claimed")
        failed = True
    # Not compared against `done`: a worker increments this after writing its DONE record, so the
    # two are legitimately one apart per worker in flight. Against `won` it is exact — the same
    # worker increments `won` first, for the same task.
    if finished > won:
        print(f"::error:: workers finished {finished} tasks between them but won {won} "
              f"— a task was finished without being won")
        failed = True
    ordered = sorted(workers, key=lambda w: w["consumedUpTo"])
    for behind, ahead in zip(ordered, ordered[1:]):
        if ahead["doneTasks"] < behind["doneTasks"]:
            print(f"::error:: {ahead['name']} replayed to {ahead['consumedUpTo']} and sees "
                  f"{ahead['doneTasks']} done, while {behind['name']} at {behind['consumedUpTo']} "
                  f"sees {behind['doneTasks']} — reading further cannot mean seeing less")
            failed = True
    if any(w["timeouts"] for w in workers):
        print("::error:: a worker gave up waiting for its own claim to come back round the log")
        failed = True

    if failed:
        return 1
    spread = max(w["consumedUpTo"] for w in workers) - min(w["consumedUpTo"] for w in workers)
    print(f"   {won} tasks, each won by exactly one worker; views agree, "
          f"{spread} record(s) of replay lag between the furthest and the nearest")
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


def projection() -> int:
    """stdin holds the projection's /stats.

    The view has to agree with its own inputs: every event it applied is counted against exactly one
    user, so the per-user totals must sum to `applied`. A mismatch means the fold lost or
    double-counted something, which no query would ever reveal on its own.
    """
    stats = json.load(sys.stdin)
    print(f"   {stats['applied']} events applied ({stats['fromReplay']} replayed, "
          f"{stats['fromFollow']} followed), {stats['users']} users, {stats['skipped']} unreadable")

    if stats["eventsAcrossUsers"] != stats["applied"]:
        print(f"::error:: the view holds {stats['eventsAcrossUsers']} events across users but applied "
              f"{stats['applied']} — the fold does not agree with its input")
        return 1
    # Nothing is asserted about the replay being non-empty here: a projection started against a
    # topic that has no history legitimately replays nothing. Whether history is re-read is the
    # subject of `rebuilt`, where there is history to re-read.
    print(f"   per-user totals sum to {stats['applied']}")
    return 0


def rebuilt() -> int:
    """stdin holds /stats after the service was restarted; APPLIED is what it had before.

    A projection that stores nothing must come back with everything, by replaying. Coming back with
    *less* is the failure this layer exists to make visible: it is what a service would do if it had
    persisted its position and not its state, and no query would ever say so.
    """
    stats = json.load(sys.stdin)
    before = int(os.environ["APPLIED"])

    print(f"   rebuilt {stats['applied']} events for {stats['users']} users "
          f"({stats['fromReplay']} of them from the replay)")
    if stats["applied"] < before:
        print(f"::error:: only {stats['applied']} events after the restart against {before} before — "
              f"the view came back incomplete")
        return 1
    if stats["fromReplay"] < before:
        print(f"::error:: the replay produced {stats['fromReplay']} events but there were {before} "
              f"before the restart — history was not re-read")
        return 1
    if stats["eventsAcrossUsers"] != stats["applied"]:
        print("::error:: the rebuilt view does not agree with its own input")
        return 1
    print(f"   all {before} came back, and the tail is running again")
    return 0


def user() -> int:
    """stdin holds one /user/{id} answer: the per-action counts must add up to the user's total."""
    view = json.load(sys.stdin)
    total = sum(view["actions"].values())
    print(f"   {view['user']}: {view['events']} events, actions {view['actions']}")
    if total != view["events"]:
        print(f"::error:: per-action counts sum to {total} but the user has {view['events']} events")
        return 1
    return 0


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "split":
        sys.exit(split())
    if mode == "roundtrip":
        sys.exit(roundtrip())
    if mode == "user":
        sys.exit(user())
    if mode == "projection":
        sys.exit(projection())
    if mode == "rebuilt":
        sys.exit(rebuilt())
    if mode == "queue":
        sys.exit(queue())
    if mode == "redistributed":
        sys.exit(redistributed())
    sys.exit(resumed(int(sys.argv[2])))
