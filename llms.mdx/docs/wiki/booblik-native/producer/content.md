# Producer (/wiki/booblik-native/producer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Producer` is responsible for accumulating individual records into batches organized by partition to maximize throughput. Instead of sending every record as a separate network request, it uses an internal accumulator to group records, significantly reducing the overhead of the broker's write path [`Producer.kt:39-42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L39-L42).

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller
    participant M as Mailbox (Channel)
    participant L as runLoop (Accumulator)
    participant B as Batch
    participant N as Network (Connection)

    C->>M: send(record)
    M->>L: Command.Append
    L->>L: accumulate(record)
    Note over L: Wait for lingerMillis or maxBatchSize
    L->>L: isAnyBatchFull?
    alt Batch Full or Timeout
        L->>B: deliver(batch)
        B->>N: connection.produce(batch)
        N-->>B: result (offsets)
        B-->>C: complete(offset)
    end"
/>

## The Accumulator and `lingerMillis` [#the-accumulator-and-lingermillis]

The core of the producer is the accumulation of records into `Batch` objects, grouped by a `Key` consisting of a topic and a partition [`Producer.kt:70-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L70-L71). The timing logic is designed to prevent indefinite delays: the window for a batch is determined by the arrival of the *first* record in that batch, using `config.lingerMillis` to set a deadline [`Producer.kt:139-141`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L139-L141). This ensures that a steady trickle of records cannot postpone a send indefinitely, which would turn a latency bound into a "latency hope" [`Producer.kt:137-138`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L137-L138).

## The `runLoop` and `select` mechanism [#the-runloop-and-select-mechanism]

The `runLoop` is the internal execution loop that processes commands from the `mailbox` channel [`Producer.kt:129-131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L129-L131). To avoid a critical bug where a cancelled receive might swallow a record and leave a caller hanging forever, the loop uses a `select` expression [`Producer.kt:154-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154-L158). This ensures that the `select` either takes the element from the channel or takes the timeout, but never both and never neither, preventing the "lost record" scenario [`Producer.kt:153`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L153).

## The `batch` function and contiguous offsets [#the-batch-function-and-contiguous-offsets]

For use cases requiring strict atomicity and contiguity, the `batch` function allows callers to bypass the accumulator entirely [`Publishing.kt:85-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L85-L90). This function ensures that the records provided in the block are sent as a single request, guaranteeing that the resulting offsets are contiguous (e.g., `base`, `base + 1`, etc.) [`Publishing.kt:98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L98).

## Lifecycle and `drainPending` [#lifecycle-and-drainpending]

When `close()` is called, the producer shuts down the `mailbox` and the underlying `dispatcher` [`Producer.kt:124-127`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L124-L127). To prevent silent data loss, the `drainPending` function is called in a `finally` block [`Producer.kt:88-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L88-L90). This function ensures that any records remaining in the `mailbox` or the `pending` map are either sent or completed with an exception [`Producer.kt:227-244`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L227-L244).

## Failure modes and `ProduceFailedException` [#failure-modes-and-producefailedexception]

If the broker returns an error code other than `ErrorCode.NONE`, the producer propagates this failure to all callers in the batch [`Producer.kt:219-222`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L219-L222). This is handled by completing the `CompletableDeferred` handles with a `ProduceFailedException` [`Producer.kt:209-211`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L209-L211).

## Performance modes: `DIRECT`, `BATCHED`, and `BATCHED_NOT_AWAITED` [#performance-modes-direct-batched-and-batched_not_awaited]

The impact of the accumulator is measured using three distinct execution patterns [`Probe.kt:79-93`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L79-L93):

| Mode                  | Description                                                                   |
| --------------------- | ----------------------------------------------------------------------------- |
| `DIRECT`              | Every record is its own request, awaited before the next.                     |
| `BATCHED`             | Through the accumulator, with records awaited before the next.                |
| `BATCHED_NOT_AWAITED` | Through the accumulator, with all records queued before awaiting the results. |

## Testing for record loss in the accumulator [#testing-for-record-loss-in-the-accumulator]

The producer's reliability is verified by `ProducerLostRecordTest`, which simulates a high-collision environment [`ProducerLostRecordTest.kt:41-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L41-L48). The test specifically drives two different topics at different rates through a single producer to ensure that the `select` mechanism and the `runLoop` do not drop records when a timeout and a new record arrival collide [`ProducerLostRecordTest.kt:52-58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L52-L58).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                 | Lines   | What is there                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | ---------------------------- |
| [`…/native/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L25-L36 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt")                                             | `25-36` | `ProducerConfig` definition  |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L58 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                                                 | `54-58` | `Producer` class declaration |
| [`…/client/Publishing.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L85-L90 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt")                                           | `85-90` | `batch` extension function   |
| [`…/conformance/Probe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt#L79-L93 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Probe.kt") | `79-93` | `Mode` enum definition       |

## Behaviour that surprising [#behaviour-that-surprising]

* The `lingerMillis` timer is reset only at the start of a batch, not on every new record arrival [`Producer.kt:137-138`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L137-L138).
* `newSingleThreadContext` is considered a "delicate API" because the thread it creates must be manually closed via `close()` to avoid leaking the thread [`Producer.kt:49-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L49-L51).
* In [`Producer.kt:205`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L205), `AckPolicy.NONE` results in an `Offset.ZERO` being returned to the caller to represent that the batch was sent.
