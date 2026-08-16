# dev/projection (/wiki/dev-projection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 3. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Broker Log] -->|1. Replay: Earliest| B(Replay Stage)
    B -->|2. Completion Signal| C(Follow Stage)
    C -->|3. Continuous Stream| D[In-Memory Projection]
    D -->|4. HTTP API| E[Client Queries]
    
    subgraph &#x22;Error Recovery&#x22;
    F[Broker Failure] -->|5. Reset| G[Full Rebuild]
    G --> A
    end"
/>

## Projection [#projection]

The core in-memory state machine that derives `UserView` from the event log. The `Projection` class manages a `ConcurrentHashMap` of users and uses `AtomicLong` to track applied and skipped events `View.kt:39-43`. It processes `Event` objects by updating user-specific statistics like event counts and action frequencies `View.kt:55-67`.

More: [Projection](dev-projection/projection)

## The build lifecycle [#the-build-lifecycle]

The transition from the replay stage to the follow stage to ensure a gapless view. The `build` function first executes a `replay` from `StartPosition.Earliest` to catch up with history `Main.kt:98`. Once the replay completes, the `progress.replayComplete` flag is set to true `Main.kt:105`, and the system transitions to a `follow` stage for each partition `Main.kt:112-130`. This ensures that the `follow` starts exactly at the `nextOffset` reported by the replay `Main.kt:117-119`.

More: [The build lifecycle](dev-projection/the-build-lifecycle)

## Progress [#progress]

Monitoring metrics including replay/follow counts, rebuild counts, and partition positions. The `Progress` class tracks the number of events from replay and follow stages using `AtomicLong` `Main.kt:146-147`, and maintains a map of current partition positions `Main.kt:149`.

## Recovery after a broker failure [#recovery-after-a-broker-failure]

The mechanism for rebuilding the entire state from the beginning of the log when the connection is lost. If an exception occurs during the build loop, the `catch` block increments the rebuild count and resets the `live` and `replayComplete` flags `Main.kt:135-140`. The entire process then restarts, re-reading the log from the beginning to ensure state consistency `Main.kt:87-143`.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                   | Lines     | What is there                                                 |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------- |
| [`…/projection/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/build.gradle.kts#L1-L7 "dev/projection/build.gradle.kts")                                                                                            | `1-7`     | Dependencies for Ktor server, CIO, and serialization          |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L44-L77 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")   | `44-77`   | The `main` function setting up the embedded server and routes |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L79-L143 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")  | `79-143`  | The `build` function containing the replay and follow logic   |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L145-L173 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt") | `145-173` | The `Progress` class for tracking internal metrics            |
| [`…/projection/View.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt#L9-L22 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt")    | `9-22`    | Data classes for `Event` and `UserView`                       |
| [`…/projection/View.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt#L39-L77 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/View.kt")   | `39-77`   | The `Projection` class implementing the state machine         |

## Behaviour that should surprise [#behaviour-that-should-surprise]

* The `Projection` class does not persist its state to disk; instead, it relies on the fact that it can rebuild the entire state by replaying the log from the beginning `View.kt:25-32`.
* If a record cannot be decoded, the `apply` function increments the `skipped` counter but continues processing the log rather than stopping `View.kt:49-53`.
* The `follow` stage uses a separate `CoroutineScope` for each partition to allow concurrent consumption of partition streams `Main.kt:111-130`.
