# booblik-core (/wiki/booblik-core)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Producer[Producers] -->|append| PW[PartitionWriter]
    PW -->|write batch| PL[PartitionLog]
    PL -->|active segment| LS[LogSegment]
    LS -->|append| SW[SegmentWriter]
    SW -->|write to disk| File[(File System)]
    
    Reader[Readers] -->|read/transferTo| PL
    PL -->|acquire| LS
    LS -->|read| File"
/>

## LogSegment [#logsegment]

The physical storage unit consisting of an append-only data file and a sparse index. It manages the actual byte-level operations, including appending records with headers and performing position lookups via a `SparseOffsetIndex` ([`LogSegment.kt:41-47`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41-L47)).

## SegmentMode [#segmentmode]

Comparison between `FILE_CHANNEL` and `MAPPED` write paths and their implications for the buffer cache:

| Mode           | Description                                                                                                                                                                                                                                                                                                                 |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FILE_CHANNEL` | Uses a plain `FileChannel` for writes; the file length is the end of the log ([`LogSegment.kt:25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L25)).                                                     |
| `MAPPED`       | Uses a memory-mapped file; the segment is pre-sized to its full capacity, and the end is marked by a zero length prefix ([`LogSegment.kt:25-289`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L25-L289)). |

## PartitionLog [#partitionlog]

Management of multiple segments, segment rolling, and the retention mechanism. It maintains an immutable list of segments that is replaced on every change to ensure thread-safe visibility without locks ([`PartitionLog.kt:18-23`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L18-L23)).

## Recovery after a crash [#recovery-after-a-crash]

The process of rebuilding the `SparseOffsetIndex` by walking record headers and verifying checksums. During recovery, the system scans the file in large chunks to find record boundaries and discards any trailing partial records or corrupted data ([`LogSegment.kt:321-373`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L321-L373)).

More: [Recovery after a crash](booblik-core/recovery-after-crash)

## The write actor and group commit [#the-write-actor-and-group-commit]

The `PartitionWriter` coroutine, mailbox processing, and the mechanics of batching and flushing. It uses a single coroutine to own the write side of a partition, draining the mailbox to group multiple `WriteCommand`s into a single batch to amortize the cost of disk barriers ([`PartitionWriter.kt:163-197`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L163-L197)).

## FetchSlice and zero-copy reading [#fetchslice-and-zero-copy-reading]

The lifecycle of a `FetchSlice` and how `transferTo` enables high-throughput streaming without heap overhead. A `FetchSlice` acts as a held claim on a contiguous run of bytes, ensuring that a segment is not retired while a `transferTo` operation is streaming data to a `WritableByteChannel` ([`PartitionLog.kt:132-145`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L132-L145)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                    | Lines   | What is there                                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------- |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41-L48 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")       | `41-48` | The `LogSegment` class which manages a single data file and its index.      |
| [`…/storage/Log.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L17-L47 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt")                            | `17-47` | The `Log` interface and the `CorruptRecordException` for checksum failures. |
| [`…/storage/PartitionLog.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33-L40 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt") | `33-40` | The `PartitionLog` class managing the collection of segments.               |
| [`…/log/PartitionWriter.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L45-L50 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt")    | `45-50` | The `PartitionWriter` class which acts as the write-side actor.             |

## Public API [#public-api]

| What                     | Where                                                                                                                                                                                           | Why                                                        |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `LogSegment`             | [`LogSegment.kt:41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41)         | Represents a physical segment of the log.                  |
| `Log`                    | [`Log.kt:17`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L17)                       | The interface for an append-only log.                      |
| `CorruptRecordException` | [`Log.kt:43`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/Log.kt#L43)                       | Thrown when a record fails its checksum verification.      |
| `PartitionLog`           | [`PartitionLog.kt:33`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33)     | Manages multiple segments for a single partition.          |
| `PartitionWriter`        | [`PartitionWriter.kt:45`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L45)   | The actor responsible for batching and writing to the log. |
| `WriterClosedException`  | [`PartitionWriter.kt:312`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L312) | Thrown when attempting to write to a closed writer.        |
| `FlushPolicy`            | [`FlushPolicy.kt:27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/FlushPolicy.kt#L27)           | Defines when the writer should trigger a disk flush.       |

## Behaviour that surprises [#behaviour-that-surprises]

* `PartitionWriter` uses a `select` expression in `awaitCommand` to avoid swallowing commands when a timeout occurs, preventing producers from hanging indefinitely ([`PartitionWriter.kt:247-250`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L247-L250)).
* `LogSegment.retire` unlinks the file from the filesystem immediately, but the file remains accessible to existing readers until their descriptors are closed ([`LogSegment.kt:217-218`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L217-L218)).
* `PartitionLog.append` will trigger a `roll()` if a record is too large to fit in the current `activeSegment`, provided it fits in a new empty segment ([`PartitionLog.kt:75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L75)).
