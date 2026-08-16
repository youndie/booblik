# State as a fold over the log (/wiki/dev/state-fold-over)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant L as Log (Broker)
    participant P as Projection Service
    participant Q as Query Surface (HTTP)

    Note over L, P: Phase 1: Replay
    L->>P: replay(Earliest)
    loop For each batch
        P->>P: projection.apply(record, offset)
    end
    P->>P: replayComplete = true

    Note over L, P: Phase 2: Follow
    L->>P: follow(At(nextOffset))
    loop Continuous stream
        P->>P: projection.apply(record, offset)
    end

    Note over P, Q: Phase 3: Query
    Q->>P: GET /user/{id}
    P-->>Q: view (state as fold)"
/>

## The Projection lifecycle [#the-projection-lifecycle]

The projection transitions from a historical reconstruction to a live stream through two distinct phases. First, it performs a `replay()` from the `Earliest` position to catch up with history, a process that ends when the stream reaches the high watermark as it was when the service started (`Main.kt:98-104`). Once the replay is complete, the service enters the `follow()` phase, which does not end, allowing the view to stay current (`Main.kt:108-128`). The seamless transition between these two phases is achieved via `RecordBatch.nextOffset`, which ensures the `follow` starts exactly where the `replay` stopped, preventing gaps or double-counting (`Main.kt:36-39`).

## The Projection state and Progress [#the-projection-state-and-progress]

The projection is designed such that the service stores nothing locally; its state is purely a functional fold over the log (`Main.kt:28-30`). To avoid silent corruption, the service deliberately does not persist its position; persisting a position without the corresponding state would result in a service that resumes at a late offset with an empty view (`Main.kt:40-42`). The current status of the service is exposed via `ProjectionStats`, which includes:

| Field            | Description                                         |
| ---------------- | --------------------------------------------------- |
| `replayComplete` | Whether the historical replay has finished          |
| `live`           | Whether the service is currently following the tail |
| `fromReplay`     | Total events processed during the replay phase      |
| `fromFollow`     | Total events processed during the follow phase      |
| `applied`        | Total events applied to the projection              |
| `users`          | Total number of unique users in the view            |

(`Main.kt:176-188`)

## Rebuilt view and the replay guarantee [#rebuilt-view-and-the-replay-guarantee]

When a service restarts, it must rebuild its entire state from the log. The `rebuilt` mode in the testing suite asserts that a restarted projection must return at least as many events as it had before the restart (`check.py:193-207`). A failure is triggered if the `applied` count is less than the previous state or if the `fromReplay` count does not match the expected history, ensuring that the view is not left incomplete (`check.py:209-211`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                   | Lines     | What is there                                                    |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---------------------------------------------------------------- |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L44-L77 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")   | `44-77`   | The main entry point and HTTP routing for the projection service |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L79-L143 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")  | `79-143`  | The `build` function managing the replay and follow lifecycles   |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L145-L188 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt") | `145-188` | The `Progress` class and `ProjectionStats` data structure        |
| [`dev/check.py`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L170-L189 "dev/check.py")                                                                                                                                               | `170-189` | Logic for asserting the integrity of the projection view         |

## Behaviour that surprises [#behaviour-that-surprises]

* **Non-persistence of position**: The `Projection` class does not persist its position because doing so without the state would lead to silent corruption (`Main.kt:40-42`).
* **The `replayComplete` signal**: The transition from history to live data is signaled by the `replayComplete` flag, which is set only after the `replay` flow completes (`Main.kt:105`).
* **At-least-once semantics**: Because the position is moved only after the consumer handles a batch, a crash between handling and saving will cause the batch to be replayed upon restart (`README.md:35-37`).
