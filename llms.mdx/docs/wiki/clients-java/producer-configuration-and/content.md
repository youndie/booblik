# Producer Configuration and Batching (/wiki/clients-java/producer-configuration-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Record Arrival] --> B{Accumulator}
    B -->|Batch Full| C[Send Request]
    B -->|Linger Timeout| C
    C --> D{AckPolicy}
    D -->|WRITTEN| E[Return Offset]
    D -->|NONE| F[Return OFFSET_UNKNOWN]
    B -->|Producer Close| G[Drain/Fail Pending]"
/>

## ProducerConfig [#producerconfig]

The configuration for the producer determines how the accumulator behaves.

| Parameter                 | Type             | Description                                                                                                                                                                                                                                                                                                    |
| ------------------------- | ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `maxBatchSize`            | `int`            | The maximum number of records per request; reaching this triggers an immediate send ([`Producer.kt:23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L23)).                                |
| `lingerMillis` / `linger` | `long` / `float` | The time a batch waits for more records; zero is not the fast setting, as it sends every record individually ([`ProducerConfig.java:15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L15)). |
| `ack` / `ackPolicy`       | `AckPolicy`      | Determines the acknowledgment behavior ([`producer.py:32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/producer.py#L32)).                                                                                                                          |

## The Accumulator [#the-accumulator]

The accumulator is the core performance driver of the client. A single record per request is highly inefficient; for example, batches of a hundred can reach 4,335,482 records/s compared to only 80,592 for single records ([`README.md:37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L37)). The accumulation loop uses a windowing logic where the deadline is calculated from the arrival of the **first** record in a batch, not the last, to prevent a steady trickle of records from postponing a send indefinitely ([`Producer.kt:139`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L139)).

## AckPolicy.NONE [#ackpolicynone]

When `AckPolicy.NONE` is selected, the client does not wait for a broker response to confirm the record's existence. Because no offset is assigned until the broker's writer reaches the batch, the client returns `OFFSET_UNKNOWN` (or `Offset.ZERO` in some implementations) to remain honest about the state of the record ([`Producer.kt:205`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L205)).

## The `batch` mechanism [#the-batch-mechanism]

The `batch` function provides a way to bypass the accumulator entirely. This is used to send a group of records as a single request, guaranteeing that the records land contiguously in the partition with consecutive offsets ([`Publishing.kt:74`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L74)).

## The `close` lifecycle and `_fail_leftovers` [#the-close-lifecycle-and-_fail_leftovers]

During shutdown, the producer must ensure no records are left hanging. The `close` method flushes queued data, and if any records remain in the mailbox after the loop has terminated, `_fail_leftovers` (or `drainPending`) is called to fail those records with an exception so the caller is not left waiting forever ([`producer.py:108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/producer.py#L108)).

## Race conditions in the accumulation loop [#race-conditions-in-the-accumulation-loop]

A critical edge case occurs when a timeout and a new record arrival happen simultaneously. Using a standard `receive` with a timeout can cause a "cancelled receive" that swallows a record, leaving the caller's `CompletableDeferred` hanging ([`Producer.kt:143`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L143)). The implementation uses `select` to ensure that the `mailbox` either takes the element or takes the timeout, but never both or neither, preventing data loss ([`Producer.kt:154`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154)).

## ProducerLostRecordTest [#producerlostrecordtest]

This test verifies the integrity of the accumulator by simulating a scenario where two different topics with different arrival rates are sent through a single producer ([`ProducerLostRecordTest.kt:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ProducerLostRecordTest.kt#L52)). It ensures that the accumulator does not drop records when the timing of a record arrival coincides with the expiration of the linger window.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                       | Lines     | What is there                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ----------------------------------------------------------- |
| [`…/java/ProducerConfig.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L14-L16 "clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java")     | `14-16`   | Default producer configuration factory.                     |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L139-L146 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")     | `139-146` | Logic regarding the accumulation window and `select` usage. |
| [`…/client/Publishing.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L74-L79 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt") | `74-79`   | Guarantees regarding contiguous offsets in a `batch`.       |
| [`…/booblik/producer.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/producer.py#L108-L112 "clients/python/booblik/producer.py")                                                                                              | `108-112` | Handling of leftover records during producer closure.       |

## Behaviour that surprises [#behaviour-that-surprises]

* `Producer.send` is not immediate; it returns a handle to a result that will only be completed once the accumulator decides to flush the batch ([`Producer.kt:75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L75)).
* `AckPolicy.NONE` results in `OFFSET_UNKNOWN` because the broker has not yet assigned an offset to a record that hasn't been processed by the writer ([`producer.py:20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/producer.py#L20)).
* `Producer.batch` is not an atomic transaction; a crash during the write can result in a partial batch surviving on disk ([`Publishing.kt:76`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L76)).
