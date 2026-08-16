# Recovery after a crash (/wiki/dev-consumer/recovery-after-crash)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer/Relay
    participant S as FileOffsetStore
    participant B as Broker (LogSegment)
    
    Note over C, B: Normal Operation
    C->>B: Fetch Batch
    B-->>C: Records + NextOffset
    C->>C: handle(records)
    C->>S: save(nextOffset)
    
    Note over C, B: Crash occurs after handle() but before save()
    
    C->>B: Reconnect
    C->>S: load()
    S-->>C: Old Offset
    C->>B: follow(oldOffset)
    B-->>C: Replayed Batch"
/>

## FileOffsetStore and atomic position updates [#fileoffsetstore-and-atomic-position-updates]

To prevent corruption of the consumer's state, `FileOffsetStore` avoids overwriting the current offset file directly. Instead, it writes the new offset to a temporary file and uses an atomic move to replace the original, ensuring that a crash during the write process does not leave a truncated or corrupted offset file ([`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57)).

## LogSegment recovery and index rebuilding [#logsegment-recovery-and-index-rebuilding]

When a broker restarts, it must reconstruct its internal lookup structures. The `LogSegment.open` function allows the system to reopen existing segments, verifying that the `nextOffset` and the index are consistent with the data stored on disk ([`RecoveryTest.kt:37-38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L37-L38)).

## SegmentMode and data integrity boundaries [#segmentmode-and-data-integrity-boundaries]

The behavior of the recovery depends on the `SegmentMode` used:

| Mode           | Recovery Behavior                                                                                                                                                                                                                                                                                                                 |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FILE_CHANNEL` | The file length acts as a boundary; a record with a header claiming more bytes than the file contains is discarded ([`RecoveryTest.kt:78-79`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L78-L79)).          |
| `MAPPED`       | The file length is pre-sized, so recovery relies on the presence of a zero-length prefix to identify the end of the log ([`RecoveryTest.kt:100-101`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L100-L101)). |

## PartitionLog segment continuity [#partitionlog-segment-continuity]

A `PartitionLog` is composed of multiple segments. Upon reopening, the system restores the continuity of the partition by identifying all existing segments and maintaining their order, ensuring the `nextOffset` reflects the total count across all segments ([`RecoveryTest.kt:117-118`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L117-L118)).

## The at-least-once guarantee in consumer and relay [#the-at-least-once-guarantee-in-consumer-and-relay]

The system provides at-least-once delivery by ensuring that the position is only updated after the work is completed. In `consumeForever`, the `checkpointing` operator saves the offset only after the `collect` block (which includes `handle`) has finished ([`Main.kt:83-84`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L83-L84)). Similarly, the relay ensures that Kafka is flushed before the booblik position is updated ([`Directions.kt:119`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L119)).

## Validation of resumed positions [#validation-of-resumed-positions]

The system distinguishes between a successful recovery and a failure based on the starting point. A consumer is considered to have failed if it starts from the beginning of the log ([`README.md:37-38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L37-L38)), whereas resuming from a position slightly behind the last known offset is considered a valid replay of a batch ([`check.py:54-55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L54-L55)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------ |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `55-57` | The atomic move mechanism using a temporary file.            |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L83-L84 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")                        | `83-84` | The checkpointing logic that ensures at-least-once delivery. |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L119 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt")                        | `119`   | The requirement to flush Kafka before checkpointing.         |

## Behaviour that surprises [#behaviour-that-surprises]

* `FileOffsetStore.save` uses a `.tmp` file to ensure that a crash during a write doesn't leave a "half-saved" position that could cause silent replays ([`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57)).
* In `booblikToKafka`, the relay does not store the Kafka key; while it preserves per-key ordering by using the key to pick the booblik partition, the key itself is lost during the crossing ([`Directions.kt:115-116`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L115-L116)).
* The `check.py` script considers a consumer "resumed" even if it starts slightly behind its last known position, because replaying a batch is the expected behavior of an at-least-once system ([`check.py:54-55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L54-L55)).
