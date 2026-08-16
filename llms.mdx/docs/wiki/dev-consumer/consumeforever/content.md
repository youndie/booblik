# consumeForever (/wiki/dev-consumer/consumeforever)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `consumeForever` function serves as the primary execution engine for the consumer service. It orchestrates the continuous reading of a specific partition from a Booblik topic, manages the lifecycle of the network connection to the broker, and ensures that the consumer's progress is persisted to local storage to allow for seamless restarts.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant S as FileOffsetStore
    participant B as BooblikSubscriber
    participant H as handle()
    participant C as checkpointing()

    loop Forever
        B->>B: follow(topic, start, partition)
        B->>H: emit(batch)
        H->>H: delay(50ms)
        H->>C: collect(batch)
        C->>H: emit(batch)
        C->>S: save(nextOffset)
    end"
/>

## The `consumeForever` lifecycle [#the-consumeforever-lifecycle]

The lifecycle begins by initializing a `FileOffsetStore` ([`Main.kt:60`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L60)) and determining the starting position for the stream. As seen in [`Main.kt:60-68`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L60-L68), the consumer attempts to load a previously saved offset from the `stateDir`; if no state exists, it defaults to `StartPosition.Earliest`. Once the starting position is determined, the function enters an infinite `while(true)` loop ([`Main.kt:75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L75)) that manages the `BooblikSubscriber` connection. The subscriber uses the `follow` method ([`Subscription.kt:120`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt#L120)) to stream `RecordBatch` objects from the broker to the consumer.

## At-least-once delivery via `checkpointing` [#at-least-once-delivery-via-checkpointing]

Data integrity is maintained through a specific execution order in the `checkpointing` operator. As implemented in [`Subscription.kt:260-265`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt#L260-L265), the `checkpointing` extension function wraps the collection process. It ensures that the `store.save` operation occurs only **after** the `emit(batch)` call has successfully passed the data to the collector. This ensures that if the `handle` function ([`Main.kt:100-107`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L100-L107)) fails or the process crashes, the offset is not updated, causing the next run to replay the same batch, thus guaranteeing at-least-once delivery.

## Recovery from broker disconnection [#recovery-from-broker-disconnection]

The consumer is designed to be resilient to network instability. If the connection to the broker is lost, the `BooblikSubscriber` throws an exception which is caught by the `try-catch` block in [`Main.kt:89-95`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L89-L95). Instead of terminating, the loop logs the failure, increments the `reconnects` metric in the `Stats` object ([`Main.kt:93`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L93)), waits for 1000ms ([`Main.kt:94`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L94)), and attempts to re-establish the connection. Upon reconnection, the consumer uses the last successfully saved offset via `StartPosition.At(it)` ([`Main.kt:68`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L68)) to resume reading exactly where it left off.

## The `Stats` snapshot mechanism [#the-stats-snapshot-mechanism]

Real-time observability is provided by the `Stats` class, which maintains the current state of the consumer. The `observe` function ([`Main.kt:125-131`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L125-L131)) is called for every batch to update the `position` (the next expected offset) and the `lag` (the distance from the high watermark). These values, along with the `lastRecord` and `reconnects` count, are captured in a `ConsumerStats` data class via the `snapshot` method ([`Main.kt:133-144`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L133-L144)) to be served over HTTP.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                               | Lines     | What is there                                                      |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------ |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L56-L97 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")                         | `56-97`   | The `consumeForever` orchestration loop and error handling.        |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L109-L145 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")                       | `109-145` | The `Stats` class and `ConsumerStats` data structure.              |
| [`…/client/Subscription.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt#L260-L265 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt") | `260-265` | The `checkpointing` extension function for at-least-once delivery. |

## Behaviour that surprise [#behaviour-that-surprise]

* **At-least-once semantics**: Because `checkpointing` saves the offset *after* the batch is emitted to the collector ([`Subscription.kt:263`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt#L263)), a crash during the `handle` function will result in the same batch being processed again upon restart.
* **Connection ownership**: A `BooblikSubscriber` owns its own connection per partition; if you follow multiple partitions, `BooblikSubscriber` will open a separate connection for each to avoid head-of-line blocking ([`Subscription.kt:157-158`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Subscription.kt#L157-L158)).
* **Non-blocking health checks**: The `Health` check does not use a simple TCP connect because a successful handshake does not guarantee the broker is actually processing requests; instead, it performs a full `metadata` request ([`Health.kt:60-61`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L60-L61)).
