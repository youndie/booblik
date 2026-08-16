# dev/queue-worker (/wiki/dev-queue-worker)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 5. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `dev/queue-worker` module implements a distributed task queue protocol built on top of a log-based broker. Instead of relying on a centralized coordinator with complex locking mechanisms, this module uses a single partition of a `claims` topic as a deterministic arbiter. Workers compete for tasks by writing claims to the log; the order of these records in the log determines the winner.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant W1 as Worker A
    participant W2 as Worker B
    participant L as Claims Log (Topic)
    participant T as Tasks Topic

    W1->>L: Write CLAIM (Task 1, Time T1)
    W2->>L: Write CLAIM (Task 1, Time T2)
    Note over L: Log Order: [Claim A, Claim B]
    L-->>W1: Replay: Claim A is winner
    L-->>W2: Replay: Claim B is loser (lease held by A)
    W1->>T: Read Task 1
    W1->>W1: Execute Work
    W1->>L: Write DONE (Task 1)"
/>

## ClaimRecord and the Claims Log [#claimrecord-and-the-claims-log]

The coordination relies on a sequence of `ClaimRecord` entries written to a specific topic. These records define the lifecycle of a task through two primary types: `CLAIM` and `DONE` ([`Claims.kt:24-25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L24-L25)). Each record contains the worker's identity, the task ID, and a timestamp (`at`) representing the worker's wall clock at the time of the claim ([`Claims.kt:18-19`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L18-L19)).

More: [ClaimRecord and the Claims Log](dev-queue-worker/claimrecord-and-the)

## ClaimState and the Deterministic Verdict [#claimstate-and-the-deterministic-verdict]

The core logic of the queue is encapsulated in `ClaimState`, which acts as a pure function that folds over the log to produce a consistent view of task ownership ([`Claims.kt:57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L57)). A key design feature is that leases are judged using the timestamp written into the `ClaimRecord` itself, rather than the reader's local clock ([`Claims.kt:50-51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L50-L51)). This ensures that all workers replaying the same log reach the exact same verdict regarding which worker holds a lease ([`Claims.kt:76-85`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L76-L85)).

More: [ClaimState and the Deterministic Verdict](dev-queue-worker/claimstate-and-the)

## Worker Lifecycle and the Work Loop [#worker-lifecycle-and-the-work-loop]

The `work` function manages the continuous loop of task acquisition and execution ([`Main.kt:151-217`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L151-L217)). The lifecycle follows these steps:

1. **Identify**: Find `claimable` tasks by checking the current `ClaimState` ([`Main.kt:171`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L171)).
2. **Claim**: Write a `CLAIM` record to the `claimsTopic` ([`Main.kt:178`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L178)).
3. **Settle**: Wait for the claim to appear in the log by observing `consumedUpTo` ([`Main.kt:192`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L192)).
4. **Execute**: Perform the work for the duration of `workMillis` ([`Main.kt:207`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L207)).
5. **Complete**: Write a `DONE` record to the log ([`Main.kt:210`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L210)).

More: [Worker Lifecycle and the Work Loop](dev-queue-worker/worker-lifecycle-and)

## Worker Metrics and Health Monitoring [#worker-metrics-and-health-monitoring]

Each worker exposes an HTTP server via Ktor to provide observability into its internal state ([`Main.kt:68`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L68)). The available endpoints are:

| Endpoint         | Method | Description                                                                                                                                                                                                                        |
| ---------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/health`        | GET    | Returns "ok"                                                                                                                                                                                                                       |
| `/stats`         | GET    | Returns `WorkerStats` including claim latency and task counts                                                                                                                                                                      |
| `/task/{offset}` | GET    | Returns `TaskState` for a specific task offset ([`Main.kt:77`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L77)) |

## Queue Report and Observability [#queue-report-and-observability]

The `Report` object is a standalone tool used to audit the entire queue by replaying the `claims` and `tasks` topics from the beginning ([`Report.kt:22`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Report.kt#L22)). It calculates the "wasted" attempts—claims that lost the race—to measure queue efficiency ([`Report.kt:82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Report.kt#L82)). It also performs a critical safety check: if the number of `DONE` records does not match the number of distinct tasks completed, it flags an error indicating a task was worked twice ([`Report.kt:98`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Report.kt#L98)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                             | Lines     | What is there                                                   |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------------------------------------- |
| [`…/queue-worker/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/build.gradle.kts#L1-L7 "dev/queue-worker/build.gradle.kts")                                                                                | `1-7`     | Dependencies for Ktor, serialization, and logback               |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L13-L33 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt")  | `13-33`   | `ClaimRecord` data class and JSON serialization logic           |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L57-L101 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt") | `57-101`  | `ClaimState` logic for replaying the log and determining leases |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L307-L341 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")      | `307-341` | `WorkerConfig` and environment variable mapping                 |
| [`…/queue/Report.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Report.kt#L22-L65 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Report.kt")  | `22-65`   | Logic for replaying topics to generate a queue report           |

## Behaviour that surprises [#behaviour-that-surprises]

* The `claimable` function uses the local clock (`now`) to determine if a lease has lapsed, but the actual ownership verdict is determined by the timestamps stored within the `ClaimRecord` itself ([`Claims.kt:101`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L101)).
* The `apply` function in `ClaimState` is designed to be a pure function, ensuring that the "verdict" is a deterministic result of the log order, regardless of when a worker reads it ([`Claims.kt:63`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L63)).
* In `Main.kt`, a worker must wait for its own claim to "settle" (appear in the log) before it begins work, meaning the worker's view of the world is always slightly behind its own actions ([`Main.kt:185`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L185)).
