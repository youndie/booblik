# Worker Lifecycle and the Work Loop (/wiki/dev-queue-worker/worker-lifecycle-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Table of Contents [#table-of-contents]

* [What this module is responsible for](#what-this-module-is-responsible-for)
* [The `work` loop and task acquisition](#the-work-loop-and-task-acquisition)
* [The `ClaimState` arbiter](#the-claimstate-arbiter)
* [Lease expiry and the `heldAt` mechanism](#lease-expiry-and-the-heldat-mechanism)
* [Redistribution and the `check-redistribution.sh` scenario](#redistribution-and-the-check-redistribution-sh-scenario)
* [WorkerStats and claim latency](#workerstats-and-claim-latency)
* [Key files](#key-files)
* [Behaviour that surprises](#behaviour-that-surprises)

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant W as Worker
    participant L as Claims Log (Partition 0)
    participant T as Tasks Topic

    W->>L: Write ClaimRecord (CLAIM)
    L-->>W: Acknowledge (Offset)
    W->>L: Follow/Read Log (Wait for offset)
    L-->>W: ClaimRecord (Self-match)
    Note over W: Work (workMillis)
    W->>L: Write ClaimRecord (DONE)
    L-->>W: Acknowledge"
/>

## The `work` loop and task acquisition [#the-work-loop-and-task-acquisition]

The main execution loop in `work` continuously attempts to acquire tasks by selecting a candidate from the `claimable` list (`Main.kt:171-172`). Taking a task is not an instantaneous operation; it requires a full round trip where the worker writes a claim and then waits for that claim to appear in its own view of the log (`Main.kt:187-193`). To mitigate collisions when many workers are idle, the `pickRandom` configuration allows workers to pick a random task from the claimable list instead of always picking the first one (`Main.kt:172`).

## The `ClaimState` arbiter [#the-claimstate-arbiter]

The `ClaimState` class acts as the arbiter of the queue by replaying the claims log to determine the current state of all tasks (`Claims.kt:46`). The verdict of who owns a task is a pure function of the log, implemented via the `apply` function, which updates the state based on `ClaimRecord` types (`Claims.kt:63-91`). This ensures that all workers reading the same log prefix reach the same conclusion regarding task ownership and completion.

## Lease expiry and the `heldAt` mechanism [#lease-expiry-and-the-heldat-mechanism]

Leases are managed by comparing the timestamp written into a claim against the timestamp of subsequent claims. A lease is considered active if the current claim's timestamp is less than the lease's expiry time, calculated via `heldAt` (`Claims.kt:41`). Crucially, the expiry is judged by the timestamp recorded in the claim itself, not the reader's local clock, which prevents disagreement between workers with clock skew (`Claims.kt:52-54`).

## Redistribution and the `check-redistribution.sh` scenario [#redistribution-and-the-check-redistributionsh-scenario]

The system handles worker failure through lease expiration. If a worker is killed via `SIGKILL` while holding a task, the task is not explicitly released; instead, it becomes `claimable` again once the lease expires in the log (`check-redistribution.sh:54-58`). The `check-redistribution.sh` script verifies that a surviving worker can successfully take over a task previously held by a "victim" worker (`check-redistribution.sh:58-62`).

## `WorkerStats` and claim latency [#workerstats-and-claim-latency]

Monitoring is provided through `WorkerStats`, which tracks the number of attempts, wins, and losses (`Main.kt:275-288`). The `claimLatencyMicros` measures the time taken for the round trip between writing a claim and seeing it settled in the log (`Main.kt:198`). High collision rates are reflected in the ratio of `won` vs `lost` attempts, where "lost" attempts represent work that happened only because no one was there to claim the task (`Report.kt:82-83`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                            | Lines     | What is there                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------ |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L56-L98 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")       | `56-98`   | The `main` function and server setup       |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L151-L217 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")     | `151-217` | The `work` loop implementation             |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L13-L40 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt") | `13-40`   | `ClaimRecord` data class and serialization |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L57-L91 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt") | `57-91`   | `ClaimState` logic and `apply` function    |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L36-L40 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt") | `36-40`   | `Lease` data class                         |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L219-L272 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")     | `219-272` | `Stats` class and `snapshot` method        |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L307-L341 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")     | `307-341` | `WorkerConfig` and environment loading     |

## Behaviour that surprises [#behaviour-that-surprises]

* The `ClaimState.apply` function is a pure function that never reads a local clock, ensuring that the verdict is a deterministic result of the log content (`Claims.kt:63-91`).
* A worker's `claimable` tasks are determined by the `now` parameter passed into the function, which allows the worker to use its local clock to decide which tasks to *try* for, even though the *win* is decided by the log's timestamps (`Claims.kt:102-104`).
* The `Report` object calculates "wasted" attempts by subtracting `wins` from total `attempts`, which is a metric of how many claims were made for tasks already held or finished (`Report.kt:82-83`).
