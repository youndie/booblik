# booblik-core (/wiki/booblik-core)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Producer[Producer] -->|append| PW[PartitionWriter]
    PW -->|WriteCommand| PL[PartitionLog]
    PL -->|append| LS[LogSegment]
    LS -->|write| SW[SegmentWriter]
    LS -->|index| SI[SparseOffsetIndex]
    Reader[Reader] -->|read/transferTo| PL
    PL -->|segmentFor| LS"
/>

## Position [#position]

The `Position` value class represents a byte position inside a single segment file, using an `Int` to match the indexing limits of `MappedByteBuffer` and `transferTo` calls [`Position.kt:6-8`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/Position.kt#L6-L8).

## PartitionLog [#partitionlog]

`PartitionLog` is the high-level abstraction of an ordered list of segments [`PartitionLog.kt:33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33). It manages the lifecycle of segments, including rolling to a new segment when the active one is full [`PartitionLog.kt:75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L75) and implementing a retention mechanism that unlinks old segments to maintain a maximum size or age [`PartitionLog.kt:178`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L178).

More: [PartitionLog](booblik-core/partitionlog)

## LogSegment [#logsegment]

A `LogSegment` is the physical storage unit consisting of a data file and a sparse index [`LogSegment.kt:28`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L28). It supports a dual read/write architecture where a single writer owns the write path while multiple readers can concurrently access the segment via `acquire` and `release` mechanisms [`LogSegment.kt:194`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L194).

More: [LogSegment](booblik-core/logsegment)

## SegmentMode [#segmentmode]

The write path used by a segment is determined by the `SegmentMode` enum [`LogSegment.kt:25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L25):

| Mode           | Description                               |
| -------------- | ----------------------------------------- |
| `FILE_CHANNEL` | Uses a standard `FileChannel` for writes. |
| `MAPPED`       | Uses a memory-mapped buffer for writes.   |

## Recovery after a crash [#recovery-after-a-crash]

During startup, `LogSegment` performs a recovery process by walking the record headers from the start of the segment to rebuild the `SparseOffsetIndex` [`LogSegment.kt:309`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L309). This process verifies each record's checksum to ensure data integrity, discarding any trailing partial records [`LogSegment.kt:354`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L354).

## The write actor and group commit [#the-write-actor-and-group-commit]

The `PartitionWriter` acts as a single-threaded actor that owns the write side of a partition [`PartitionWriter.kt:18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L18). It uses a mailbox to receive `WriteCommand` batches and implements "group commit" by draining the mailbox to process multiple commands in a single batch, only triggering a costly `Log.force` if any command in the group requires it [`PartitionWriter.kt:34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L34).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                     | Lines    | What is there                                      |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | -------------------------------------------------- |
| [`…/booblik/Position.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/Position.kt#L19-L20 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/Position.kt")                              | `19-20`  | The `Position` value class definition.             |
| [`…/storage/Log.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L17-L33 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt")                             | `17-33`  | The `Log` interface and `CorruptRecordException`.  |
| [`…/storage/PartitionLog.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33-L258 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt") | `33-258` | The `PartitionLog` class and its companion object. |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41-L407 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")       | `41-407` | The `LogSegment` class and its recovery logic.     |
| [`…/log/PartitionWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L45-L312 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt")    | `45-312` | The `PartitionWriter` actor and its run loop.      |
| [`…/storage/SegmentWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L18 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt")   | `18`     | The `SegmentWriter` interface.                     |

## Public API [#public-api]

| What                     | Where                                                                                                                                                                                           | Why                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| `Position`               | [`Position.kt:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/Position.kt#L19)                     | Represents a byte offset within a segment.          |
| `Log`                    | [`Log.kt:17`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L17)                       | Interface for append-only log operations.           |
| `CorruptRecordException` | [`Log.kt:43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L43)                       | Thrown when a record fails checksum verification.   |
| `PartitionLog`           | [`PartitionLog.kt:33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33)     | High-level partition management.                    |
| `LogSegment`             | [`LogSegment.kt:41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41)         | Physical segment storage and indexing.              |
| `PartitionWriter`        | [`PartitionWriter.kt:45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L45)   | The actor responsible for writing to a partition.   |
| `WriterClosedException`  | [`PartitionWriter.kt:312`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L312) | Thrown when attempting to write to a closed writer. |
| `SegmentWriter`          | [`SegmentWriter.kt:18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L18)   | Interface for segment-level writing.                |

## Behaviour that surprises [#behaviour-that-surprises]

* `PartitionWriter.append` returns `null` when using `AckPolicy.NONE` because the offset is not yet assigned by the actor [`PartitionWriter.kt:130`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L130).
* `LogSegment.acquire` may return `false` if the segment was retired while the caller was attempting to acquire it, requiring a retry [`LogSegment.kt:195`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L195).
* `PartitionLog.transferTo` is designed to never cross a segment boundary because it uses a single file descriptor for the operation [`PartitionLog.kt:163`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L163).
