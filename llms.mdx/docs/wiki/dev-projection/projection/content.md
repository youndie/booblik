# Projection (/wiki/dev-projection/projection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

Documentation for the Projection module, focusing on the read model implementation and its verification.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant Log as Booblik Log
    participant Replay as Replay Phase (Earliest)
    participant Follow as Follow Phase (Tail)
    participant State as Projection State (In-Memory)

    Log->>Replay: Stream all historical records
    Replay->>State: apply(record, offset)
    Note over Replay: Replay completes when high watermark reached
    Replay->>Follow: Pass nextOffset per partition
    Log->>Follow: Stream new records from nextOffset
    Follow->>State: apply(record, offset)
    Note over State: State is purely a fold over the log"
/>

## The Projection lifecycle [#the-projection-lifecycle]

The lifecycle of a projection is split into two distinct phases to ensure the view is both complete and current. It begins with a `replay()` phase that reads from the `Earliest` position to build the initial state, and transitions to a `follow()` phase once the history has been exhausted [`Main.kt:98-110`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/Main.kt#L98-L110).

## The Projection state machine [#the-projection-state-machine]

The state is managed within the `Projection` class, where the `apply` function performs a functional fold over the incoming stream [`View.kt:44-67`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L44-L67). The `UserView` is updated by merging new actions into the existing map and incrementing event counts. Crucially, the correctness of `lastAction` depends on the fact that all events for a single user are routed to the same partition, ensuring they arrive in order [`View.kt:34-37`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L34-L37).

## Recovery after a crash [#recovery-after-a-crash]

The design follows a "persist neither" philosophy to avoid silent corruption. Because the `Projection` does not persist its position to a volume, a restart forces a complete rebuild from the beginning of the log [`View.kt:27-32`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L27-L32). This ensures that the view is always a complete reflection of the log, rather than a potentially truncated view that resumes from a stale offset [`README.md:140-144`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L140-L144).

## Verification of the read model [#verification-of-the-read-model]

The verification suite, primarily implemented in `dev/check-projection.sh`, asserts several invariants:

* **Rebuild Completeness**: After a restart, the `applied` count must be at least as large as the count before the restart [`check.py:204-207`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L204-L207).
* **View Consistency**: The sum of all `events` across all users must exactly match the total number of `applied` events [`check.py:181-184`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L181-L184).
* **User Integrity**: For any specific user, the sum of counts in the `actions` map must equal the total `events` count for that user [`check.py:224-226`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L224-L226).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                   | Lines     | What is there                                             |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------------------------------- |
| [`…/projection/View.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt#L10-L22 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt")   | `10-22`   | `Event` and `UserView` data classes                       |
| [`…/projection/View.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt#L39-L77 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt")   | `39-77`   | `Projection` class containing the state and `apply` logic |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L44-L77 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")   | `44-77`   | `main` function setting up the Ktor server and routes     |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L79-L143 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")  | `79-143`  | `build` function managing the replay and follow loops     |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L145-L188 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt") | `145-188` | `Progress` and `ProjectionStats` for monitoring           |

## Behaviour that surprising [#behaviour-that-surprising]

* The `apply` function in `Projection` uses `ConcurrentHashMap.compute` to ensure thread-safe updates to user views during the `follow` phase [`View.kt:55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L55).
* The `decode` function in `View.kt` uses `runCatching` to silently skip unreadable records, incrementing a `skipped` counter instead of crashing the projection [`View.kt:48-53`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L48-L53).
* The `top` function in `Projection` performs a full sort of all users in memory to return the requested limit [`View.kt:71`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/View.kt#L71).
