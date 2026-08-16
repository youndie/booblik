# ClaimRecord and the Claims Log (/wiki/dev-queue-worker/claimrecord-and-the)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant W1 as Worker A
    participant Log as Claims Log (Partition 0)
    participant W2 as Worker B

    W1->>Log: Append ClaimRecord(CLAIM, worker=&#x22;A&#x22;, task=7, at=1000)
    Note over Log: Log is the source of truth
    Log-->>W1: Acknowledge (Offset 1)
    W1->>Log: Append ClaimRecord(DONE, worker=&#x22;A&#x22;, task=7)
    Note over W2: W2 replays log
    Log-->>W2: Read Offset 1 (Claim A)
    Note over W2: W2 calculates state.holds(&#x22;A&#x22;, 7, 1000)
    Log-->>W2: Read Offset 2 (Done A)
    Note over W2: W2 calculates state.done.contains(7)"
/>

## ClaimRecord [#claimrecord]

The `ClaimRecord` is the fundamental unit of the claims log, representing either an attempt to take a task or the signal that a task is finished. As defined in [`Claims.kt:14-21`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L14-L21), the record contains:

| Field         | Type     | Description                         |
| ------------- | -------- | ----------------------------------- |
| `type`        | `String` | Either `claim` or `done`            |
| `worker`      | `String` | The identifier of the worker        |
| `task`        | `Long`   | The unique ID of the task           |
| `at`          | `Long`   | The claimant's wall clock timestamp |
| `leaseMillis` | `Long`   | The duration the lease is valid for |

Serialization is handled via `kotlinx.serialization`, with `encode` and `decode` methods provided in the companion object ([`Claims.kt:28-31`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L28-L31)).

## ClaimState [#claimstate]

`ClaimState` is a pure, immutable data structure that represents the current view of the queue by replaying the log. The core logic resides in the `apply` function ([`Claims.kt:62-91`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L62-L91)), which takes a `ClaimRecord` and the `nextOffset` to produce a new state.

The state tracks:

* `consumedUpTo`: The last processed offset in the log.
* `leases`: A map of tasks to their current `Lease` ([`Claims.kt:59`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L59)).
* `done`: A set of completed task IDs ([`Claims.kt:60`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L60)).

## The Lease Lifecycle [#the-lease-lifecycle]

The lifecycle of a task is managed through the sequence of records in the log. A task becomes "claimable" when it is not in the `done` set and no active lease exists ([`Claims.kt:101-104`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L101-L104)).

1. **Claiming**: A worker writes a `CLAIM` record. The worker must wait for the log to "settle" by reading its own claim back to ensure it actually won the race ([`Main.kt:184-193`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L184-L193)).
2. **Holding**: A task is held if the worker's name and the timestamp `at` match the current lease in the state ([`Claims.kt:95-98`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L95-L98)).
3. **Releasing**: A worker writes a `DONE` record, which removes the task from active leases and adds it to the `done` set ([`Claims.kt:67-73`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L67-L73)).

## Clock Skew and Determinism [#clock-skew-and-determinism]

A critical design requirement is that the verdict is a pure function of the log, ensuring all readers reach the same conclusion even if their local clocks differ. As noted in [`Claims.kt:47-51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L47-L51), the `Lease` is judged by comparing the timestamp written *into the claim itself* against the `at` value of the claim, rather than using the reader's local `now()`. This ensures that skew only affects *when* a worker tries to claim a task, not *who* wins the claim once it is written to the log.

## ClaimStateTest [#claimstatetest]

The `ClaimStateTest` class verifies the correctness of the state machine through several property-based scenarios ([`ClaimStateTest.kt:22-107`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L22-L107)):

* **Race Conditions**: Verifies that the first claim in the log wins and subsequent claims within the lease period are ignored ([`ClaimStateTest.kt:45-50`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L45-L50)).
* **Lease Expiry**: Ensures a claim written after a previous lease has lapsed successfully takes over the task ([`ClaimStateTest.kt:54-59`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L54-L59)).
* **Completion**: Confirms that once a `DONE` record is processed, the task is no longer claimable ([`ClaimStateTest.kt:64-69`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L64-L69)).
* **Forward Compatibility**: Asserts that unknown record types only advance the `consumedUpTo` offset without disturbing existing leases ([`ClaimStateTest.kt:100-107`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L100-L107)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                     | Lines     | What is there                                                                        |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------------------ |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L14-L43 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt")                          | `14-43`   | Definition of `ClaimRecord` and `Lease` data classes and their serialization logic.  |
| [`…/queue/Claims.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt#L57-L91 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Claims.kt")                          | `57-91`   | The `ClaimState` class containing the `apply` logic for replaying the log.           |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L151-L217 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt")                              | `151-217` | The `work` loop in the worker, including the claim-write and settlement-check logic. |
| [`…/queue/ClaimStateTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt#L22-L107 "dev/queue-worker/src/test/kotlin/ru/workinprogress/booblik/dev/queue/ClaimStateTest.kt") | `22-107`  | Unit tests for the `ClaimState` state machine.                                       |

## Behaviour that surprises [#behaviour-that-surprises]

* **The Settlement Round Trip**: A worker does not consider itself the owner of a task immediately after sending a claim; it must wait until the claim is read back from the log to confirm it wasn't superseded by a race (`Main.kt:184-193`).
* **Clock Independence**: The `holds` function in `ClaimState` (`Claims.kt:95-98`) uses the timestamp stored in the record rather than the current system time, making the state machine deterministic across different machines.
* **The "Wasted" Attempt**: In `Report.kt:82`, an attempt is considered "lost" if it was written to the log but did not result in a successful lease, representing work that happened because a worker was "in step" with others.
