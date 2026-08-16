# Producer (/wiki/booblik-client/producer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Producer` is responsible for accumulating individual records into batches organized by topic and partition to maximize throughput. Instead of sending every record immediately, it waits until either a maximum batch size is reached or a time limit (`lingerMillis`) expires, significantly reducing the overhead of network round-trips and broker writes ([`Producer.kt:23-34`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L23-L34)).

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller
    participant P as Producer (runLoop)
    participant M as Mailbox (Channel)
    participant B as Pending Batches (Map)
    participant N as Network (BooblikConnection)

    C->>M: send(record)
    M->>P: Command.Append
    P->>B: accumulate(record)
    Note over P: Wait for lingerMillis or maxBatchSize
    P->>B: isAnyBatchFull?
    P->>N: deliver(batch)
    N-->>P: result (Offset/Error)
    P->>C: complete(Offset)"
/>

## The Accumulator [#the-accumulator]

The core of the producer is the accumulation logic that groups records by a `Key` (topic and partition) as seen in [`Producer.kt:235-238`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L235-L238). The `ProducerConfig` determines how these batches behave:

| Parameter      | Type        | Description                                        |
| -------------- | ----------- | -------------------------------------------------- |
| `maxBatchSize` | `Int`       | Reached first, the batch goes immediately.         |
| `lingerMillis` | `Long`      | How long an incomplete batch waits for company.    |
| `ackPolicy`    | `AckPolicy` | Determines when the caller is notified of success. |

The timing logic is specifically designed so that the `lingerMillis` window starts from the arrival of the *first* record in a batch, not the last, to prevent a steady trickle of records from postponing a send indefinitely ([`Producer.kt:47-49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L47-L49)).

## Command Loop and `select` semantics [#command-loop-and-select-semantics]

The `runLoop` function manages the lifecycle of incoming commands using a `select` expression to multiplex between receiving new commands from the `mailbox` and waiting for the `lingerMillis` timeout ([`Producer.kt:154-158`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154-L158)).

A critical implementation detail is the use of `select` with `onReceiveCatching` rather than `withTimeoutOrNull(mailbox.receive())`. This is because a cancelled `receive` can swallow an element from the channel without delivering it to the logic, causing the caller's `CompletableDeferred` to hang forever ([`Producer.kt:143-146`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L143-L146)).

## Batch Delivery and `AckPolicy` [#batch-delivery-and-ackpolicy]

When a batch is ready, `deliver` is called to transmit the records via the connection ([`Producer.kt:195-200`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L195-L200)). The handling of the response depends on the `AckPolicy`:

* If the result is `null` (used for `AckPolicy.NONE`), the producer completes all handles with `Offset.ZERO` to indicate the batch was sent, even though no broker acknowledgement was received ([`Producer.kt:201-206`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L201-L206)).
* If an `ErrorCode` is returned, the producer completes the handles with a `ProduceFailedException` ([`Producer.kt:208-211`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L208-L211)).
* On success, it calculates consecutive offsets for the batch ([`Producer.kt:214-216`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L214-L216)).

## `drainPending` and Connection Failure [#drainpending-and-connection-failure]

If the producer is closed or the connection fails, the `drainPending` function is invoked to ensure no callers are left hanging ([`Producer.kt:222-224`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L222-L224)). It iterates through all currently pending batches and completes their `CompletableDeferred` handles with a `ConnectionClosedException` ([`Producer.kt:223`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L223)). It then drains any remaining commands in the `mailbox` to ensure the coroutine terminates cleanly ([`Producer.kt:227-232`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L227-L232)).

## Race conditions in multi-topic streams [#race-conditions-in-multi-topic-streams]

A specific edge case exists when a single `Producer` handles multiple topics at different arrival rates. If a record arrives exactly when the `lingerMillis` timer expires, a race condition in the `runLoop` can cause a `receive` operation to be cancelled, swallowing a record and causing a hang ([`Producer.kt:154-158`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154-L158)). This is verified by tests that drive two topics at different rates to force the collision ([`ProducerLostRecordTest.kt:52-53`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L52-L53)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                    | Lines     | What is there                                        |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---------------------------------------------------- |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L21-L34 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                    | `21-34`   | `ProducerConfig` definition and batching parameters. |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L86 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                    | `54-86`   | `Producer` class definition and `send` method.       |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L123-L173 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                  | `123-173` | The `runLoop` implementation and `select` logic.     |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L195-L220 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                  | `195-220` | The `deliver` method and acknowledgement handling.   |
| [`…/net/ProducerLostRecordTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L41-L53 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt") | `41-53`   | Test case for multi-topic rate collisions.           |

## Behaviour that surprising [#behaviour-that-surprising]

* The `send` function in `Producer` does not actually send the record to the wire immediately; it merely queues it in a `mailbox`, meaning the returned `CompletableDeferred` only completes once the batch is actually delivered ([`Producer.kt:75-76`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L75-L76)).
* In `runLoop`, the `lingerMillis` timer is calculated based on the first record of a batch, which prevents a "steady trickle" of records from indefinitely postponing a send ([`Producer.kt:47-49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L47-L49)).
* The `deliver` method uses `batch.answers.forEachIndexed` to assign consecutive offsets to records in a batch, assuming the broker assigns them sequentially ([`Producer.kt:214-216`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L214-L216)).
