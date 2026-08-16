# Producer (/wiki/booblik-client/producer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Producer` is responsible for accumulating individual records into batches to maximize throughput by reducing the number of network requests. It acts as an intermediary between the user's `send` calls and the `BooblikConnection`, managing the lifecycle of records from their arrival in a mailbox to their delivery to the broker.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant U as User (Coroutine)
    participant M as Mailbox (Channel)
    participant R as runLoop (Accumulator)
    participant B as Batch (Pending)
    participant C as Connection

    U->>M: send(record)
    M->>R: Command.Append
    R->>B: accumulate(record)
    Note over R: Wait for lingerMillis or maxBatchSize
    R->>C: deliver(batch)
    C-->>R: result (Offset/Error)
    R->>U: complete(Offset)"
/>

## The Accumulator [#the-accumulator]

The core of the producer is the accumulation loop, which manages pending records in a `LinkedHashMap` of `Key` to `Batch` ([`Producer.kt:70-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L70-L71)). Instead of sending records immediately, the producer waits until either the `maxBatchSize` is reached or the `lingerMillis` window expires ([`Producer.kt:23-32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L23-L32)). Crucially, the timer for the `lingerMillis` window starts from the arrival of the *first* record in a batch, not the last, to prevent a steady trickle of records from indefinitely postponing a send ([`Producer.kt:47-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L47-L49)).

## Command.Append and Command.Flush [#commandappend-and-commandflush]

The `runLoop` operates by consuming commands from a buffered `mailbox` channel ([`Producer.kt:59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L59)).

* `Command.Append`: Adds a record to the corresponding partition's batch via `accumulate` ([`Producer.kt:132-134`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L132-L134)).
* `Command.Flush`: Triggers an immediate `sendAll()` and completes the command's deferred value, forcing all currently accumulated batches to be delivered to the connection ([`Producer.kt:126-128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L126-L128)).

## AckPolicy and Batch Delivery [#ackpolicy-and-batch-delivery]

The `deliver` function handles the actual network transmission and the subsequent response from the broker ([`Producer.kt:195-220`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L195-L220)). The behavior of the caller depends on the `AckPolicy`:

| Policy    | Behavior                                                                                                                                                                                                                                                                                                                                         |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `WRITTEN` | The `CompletableDeferred` is completed with the broker-provided offset ([`Producer.kt:214-216`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L214-L216)).                                                                    |
| `NONE`    | The `CompletableDeferred` is completed with `Offset.ZERO` to indicate the batch was sent, even though no broker confirmation was received ([`Producer.kt:204-206`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L204-L206)). |

## The Lost Record Edge Case [#the-lost-record-edge-case]

A critical edge case occurs when a `receive` operation on the `mailbox` is cancelled (e.g., due to a timeout in `select`) at the exact moment a record is being delivered to the channel. If `withTimeoutOrNull` were used instead of `select`, a cancelled receive could swallow a record, leaving the caller's `CompletableDeferred` hanging forever ([`Producer.kt:143-146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L143-L146)). This specific race condition is verified in `ProducerLostRecordTest.kt:52-94`, where two topics at different rates are used to force the collision.

## Batching Performance and Throughput [#batching-performance-and-throughput]

Batching is the single largest performance factor in the project. As noted in [`benchmarking.md:140-146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/benchmarking.md#L140-L146), the difference between sending records one at a time and sending them in batches of 100 can result in a performance increase of approximately 54× ([`Producer.kt:39-42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L39-L42)). The `ProducerConfig` allows tuning this via `maxBatchSize` and `lingerMillis` ([`ProducerConfig.java:4-16`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L4-L16)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                     | Lines     | What is there                                         |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------- |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L21-L34 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                     | `21-34`   | `ProducerConfig` data class and its default values    |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L173 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                    | `54-173`  | The `Producer` class and its `runLoop` implementation |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L253-L263 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                   | `253-263` | The `Command` sealed interface for the mailbox        |
| [`…/net/ClientTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ClientTest.kt#L84-L96 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ClientTest.kt")                                      | `84-96`   | Tests verifying batching and `flush` behavior         |
| [`…/net/ProducerLostRecordTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L52-L103 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt") | `52-103`  | Test for the race condition in the accumulator        |

## Behaviour that surprises [#behaviour-that-surprises]

* The `lingerMillis` timer is not reset on every new record; it is fixed from the moment the first record of a batch arrives ([`Producer.kt:47-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L47-L49)).
* Using `select` with `onReceiveCatching` is mandatory in the `runLoop` to prevent the producer from "swallowing" records during a timeout, which would cause callers to hang ([`Producer.kt:154-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154-L158)).
* `AckPolicy.NONE` does not actually wait for a broker response, but it still completes the record's `CompletableDeferred` with `Offset.ZERO` to maintain a consistent API ([`Producer.kt:204-206`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L204-L206)).
