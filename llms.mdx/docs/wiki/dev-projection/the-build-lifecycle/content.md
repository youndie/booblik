# The build lifecycle (/wiki/dev-projection/the-build-lifecycle)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `dev/projection` module implements a read model where state is a pure function of the event log. It is responsible for consuming a stream of events from a `BooblikSubscriber`, building an in-memory view, and providing an HTTP query surface.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant B as Broker (Log)
    participant P as Projection (Service)
    participant Q as Query Surface (HTTP)

    Note over P: Start Replay Phase
    P->>B: replay(Earliest)
    B-->>P: Batch (History)
    P->>P: apply(record)
    Note over P: Replay Complete (replayComplete = true)
    
    Note over P: Start Follow Phase
    P->>B: follow(At(nextOffset))
    B-->>P: Batch (Live Stream)
    P->>P: apply(record)
    P->>Q: respond(view)
    
    Note over P: Failure / Restart
    P->>P: Wipe State
    P->>B: replay(Earliest)
    P->>P: Rebuild State"
/>

## The replay and follow transition [#the-replay-and-follow-transition]

To ensure the query surface transitions from "building" to "current" without losing or duplicating data, the projection uses a two-phase approach. First, it performs a `replay()` to consume history. The `RecordBatch.nextOffset` property is critical here; it reports where each partition stopped during the replay, allowing the subsequent `follow()` phase to start exactly at that offset [`Main.kt:137-139`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L137-L139). This mechanism ensures that the view catches up to the high watermark without gaps or redundant processing.

## The statelessness of Projection [#the-statelessness-of-projection]

A core design principle is that the projection stores nothing on disk. While a consumer might persist its position, the projection's state is purely in-memory. As noted in the documentation, persisting a position without the corresponding state would lead to silent corruption [`README.md:141-144`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L141-L144). Instead, upon any restart, the service must rebuild its entire state by replaying the log from the beginning to ensure the view is consistent with the truth of the log.

## Rebuild verification [#rebuild-verification]

The integrity of the rebuild process is verified by ensuring that a restarted service recovers at least the same amount of data it held previously. The `check-projection.sh` script performs this by capturing the `applied` count before a restart and asserting that the rebuilt view contains at least that many events [`check-projection.sh:42-47`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-projection.sh#L42-L47). This prevents a scenario where a service resumes from a late offset with an empty view, which would result in a "successful" but incorrect state.

## At-least-once semantics in checkpointing [#at-least-once-semantics-in-checkpointing]

The system guarantees at-least-once delivery through the mechanics of `checkpointing`. The offset is only saved after the collector has successfully handled a batch of records [`SubscriptionTest.kt:143-172`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SubscriptionTest.kt#L143-L172). If an error occurs during the `apply` phase, the checkpoint is not updated, meaning the next attempt will replay the same batch, ensuring no event is lost at the cost of potential duplicates.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                    | Lines     | What is there                                                      |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------ |
| [`…/projection/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L145-L173 "dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt")  | `145-173` | The `Progress` class tracking replay/follow status and statistics. |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L129-L135 "dev/README.md")                                                                                                                                             | `129-135` | Explanation of the `replay()` and `follow()` mechanics.            |
| [`dev/check-projection.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-projection.sh#L42-L47 "dev/check-projection.sh")                                                                                                                 | `42-47`   | Logic for verifying the rebuilt state against the previous state.  |
| [`dev/check.py`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L170-L189 "dev/check.py")                                                                                                                                                | `170-189` | Logic for asserting that the rebuilt view matches the input.       |
| [`…/net/SubscriptionTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SubscriptionTest.kt#L143-L172 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SubscriptionTest.kt") | `143-172` | Test case for at-least-once semantics during failures.             |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Corruption Risk**: If a service were to persist its position without its state, it would result in a silent corruption rather than an optimization [`README.md:41-42`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L41-L42).
* **Replay Completion**: The `replayComplete` flag is only set once the `replay` flow from the subscriber actually completes [`Main.kt:105`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L105).
* **Non-monotonicity in `follow`**: While `follow` ensures the view stays current, the `follow` phase does not end, unlike `replay` which terminates once the high watermark is reached [`Main.kt:35`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/projection/src/main/kotlin/ru/workinprogress/booblik/dev/projection/Main.kt#L35).
