# ClaimState and the Deterministic Verdict (/wiki/dev-queue-worker/claimstate-and-the)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

Documentation for the deterministic state machine used in the queue worker module.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant W1 as Worker A (Claimant)
    participant L as Claims Log (Partition)
    participant W2 as Worker B (Observer)
    
    W1->>L: Write CLAIM (at=1000, lease=30s)
    L-->>W2: Broadcast CLAIM
    Note over W2: W2 calculates state via fold()
    W2->>W2: Lease active until 1000 + 30s
    W1->>L: Write CLAIM (at=1031, lease=30s)
    L-->>W2: Broadcast CLAIM
    Note over W2: W2 sees 1031 > 1000 + 30
    W2->>W2: W2 decides Worker B won task"
/>

## ClaimRecord and the Log Format [#claimrecord-and-the-log-format]

The state of the queue is driven by a sequence of `ClaimRecord` entries written to a specific partition of the `claims` topic. As defined in `Claims.kt:14-21`, each record contains the worker's identity, the task ID, and a timestamp. The log format distinguishes between two primary types:

| Type    | Constant            | Description                                                 |
| ------- | ------------------- | ----------------------------------------------------------- |
| `claim` | `ClaimRecord.CLAIM` | An attempt by a worker to acquire a lease on a task.        |
| `done`  | `ClaimRecord.DONE`  | A notification that a task has been successfully completed. |

## ClaimState: The Pure Function of the Log [#claimstate-the-pure-function-of-the-log]

The `ClaimState` class acts as a deterministic state machine. The "verdict" of the queue is a pure function of the log: by replaying all `ClaimRecord` entries in order using the `apply` function (`Claims.kt:62-92`), any worker can reconstruct the current state of all leases and completed tasks. Because `apply` only relies on the data within the records and not the reader's system clock, the state is perfectly reproducible across different machines.

## Lease Expiry and the Timestamp-based Verdict [#lease-expiry-and-the-timestamp-based-verdict]

A critical design choice is how leases expire. Instead of using the reader's current time, the expiration is judged by the timestamp `at` written into the claim itself. As explained in `Claims.kt:48-51`, a claim is considered "taken" if the task is already in the `done` set or if the new claim's timestamp falls within the duration of an existing lease (`Claims.kt:77`). This ensures that if Worker A claims a task at $T=1000$ for 30s, Worker B's claim at $T=1031$ will win, even if Worker B's local clock thinks it is only $T=1010$.

## The claimable Mechanism and Local Clock Skew [#the-claimable-mechanism-and-local-clock-skew]

While the final verdict is deterministic, workers still need to decide which tasks they *should* attempt to claim. This is handled by the `claimable` function (`Claims.kt:101-104`), which takes a `now: Long` parameter representing the reader's local clock. This function filters tasks that are not yet `done` and whose current lease has not yet lapsed according to the local time. This is the only place where clock skew matters: it affects *when* a worker decides to try for a task, but it never changes the outcome of who actually wins the task once the claim is written to the log.

## Verification of Determinism in ClaimStateTest [#verification-of-determinism-in-claimstatetest]

The correctness of this logic is verified in `ClaimStateTest.kt`. The tests ensure that:

* The first claim in a sequence wins if subsequent claims land within the lease period (`ClaimStateTest.kt:45-50`).
* A claim arriving after a lease has lapsed successfully takes over the task (`ClaimStateTest.kt:53-60`).
* Once a task is marked as `DONE`, it cannot be claimed again (`ClaimStateTest.kt:63-69`).
* Unknown record types do not disturb existing leases but do advance the `consumedUpTo` offset (`ClaimStateTest.kt:100-107`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                     | Lines    | What is there                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | -------------------------------------------------- |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L14-L43 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt")                          | `14-43`  | Definition of `ClaimRecord` and its serialization. |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L57-L103 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt")                         | `57-103` | The `ClaimState` state machine and lease logic.    |
| [`…/queue/ClaimStateTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L44-L107 "dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt") | `44-107` | Unit tests for the deterministic state machine.    |

## Behaviour that surprise [#behaviour-that-surprise]

* **`ClaimState.apply`** is a pure function that ignores the reader's clock; it only cares about the timestamps embedded in the `ClaimRecord` objects.
* **`claimable`** is the only function where the local clock is allowed to influence the logic, and it only affects the *attempt* to claim, not the *result* of the claim.
* **`ClaimRecord.decode`** uses `runCatching` to return `null` for unknown types, allowing the state machine to skip unknown records without crashing or losing its position in the log.
