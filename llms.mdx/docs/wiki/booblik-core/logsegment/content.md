# LogSegment (/wiki/booblik-core/logsegment)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

This documentation describes the `LogSegment` component within the `booblik-core` module.

## What this module is responsible for [#what-this-module-is-responsible-for]

The `LogSegment` is the fundamental unit of storage in the `booblik` log, representing an append-only data file paired with a sparse index. It manages the lifecycle of data on disk, ensuring that records are written with integrity, can be recovered after a crash, and can be read efficiently via both zero-copy and heap-based paths.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph LogSegment
        Writer[SegmentWriter] -->|Appends| File[(Data File)]
        Index[SparseOffsetIndex] -->|Stores| Entry[Index Entry]
        Reader[FileChannel] -->|Reads| File
        Writer -->|Updates| nextOffset[nextOffset]
    end
    Reader -->|Lookup| Index
    Index -->|Provides| Position
    Position -->|Forward Scan| Reader"
/>

## LogSegment Architecture [#logsegment-architecture]

A `LogSegment` acts as a coordinator for three primary components: a `SegmentWriter` for appending data, a `FileChannel` for reading, and a `SparseOffsetIndex` for locating records. The architecture is designed to separate read and write concerns; the writer is owned by a single coroutine, while readers run concurrently without locks, relying on the fact that the file is append-only and the `nextOffset` is published only after bytes are written (`LogSegment.kt:30-34`).

## SegmentMode: FILE\_CHANNEL vs MAPPED [#segmentmode-file_channel-vs-mapped]

The segment supports two distinct write paths, which differ in how they handle file sizing and the end-of-log sentinel:

| Mode           | File Sizing                                                                    | End-of-Log Sentinel                           |
| -------------- | ------------------------------------------------------------------------------ | --------------------------------------------- |
| `FILE_CHANNEL` | The file length is determined by the actual data written (`LogSegment.kt:287`) | The file length itself acts as the boundary   |
| `MAPPED`       | The file is pre-sized to its full `capacity` (`LogSegment.kt:288`)             | A zero-length prefix marks the end of the log |

## SparseOffsetIndex Mechanics [#sparseoffsetindex-mechanics]

To avoid the overhead of a full index, the `SparseOffsetIndex` uses intervals to store entries. When a `positionOf(offset)` is called, the index provides the greatest entry at or below the target, and the system performs a forward scan over length prefixes to find the exact position (`LogSegment.kt:96-108`). This mechanism is designed to handle large `baseOffset` values without truncation by storing offsets relative to the base (`SparseOffsetIndexTest.kt:52-57`).

## Recovery after a crash [#recovery-after-a-crash]

During startup, the `recover` function reconstructs the index by walking the record headers from the start of the segment (`LogSegment.kt:321-330`). The recovery logic is robust against crashes: it discards "torn" records where a length prefix claims more bytes than are available or where the checksum does not match the stored value (`LogSegment.kt:354-365`). In `MAPPED` mode, a crash that leaves an orphaned body without a header is treated as the end of the log (`RecoveryTest.kt:97-101`).

## Reader Lifecycle and Concurrency [#reader-lifecycle-and-concurrency]

Concurrency is managed through a registration pattern to ensure segments are not closed while readers are active. A reader must call `acquire()` to register itself, which increments an `AtomicInteger` (`LogSegment.kt:195-196`). When a segment is `retire()`-ed, it is unlinked from the file system, but the file descriptor remains open until the last reader calls `release()`, at which point `closeNow()` is invoked (`LogSegment.kt:209-210`).

## Zero-copy and Heap-based Reading [#zero-copy-and-heap-based-reading]

The segment provides two distinct reading paths:

* **Zero-copy:** The `transferTo` method uses `FileChannel.transferTo` to stream bytes directly to a `WritableByteChannel` (like a socket) without touching the JVM heap (`LogSegment.kt:158-166`).
* **Heap-based:** The `read(offset)` method allocates a `ByteArray` and performs a manual checksum verification to ensure data integrity (`LogSegment.kt:130-131`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                | Lines     | What is there                                                            |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------ |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L41-L48 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")   | `41-48`   | The `LogSegment` class definition and its primary properties.            |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L261-L270 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt") | `261-270` | The `open` companion function for initializing and recovering a segment. |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L321-L343 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt") | `321-343` | The `recover` function implementation for rebuilding the index.          |

## Behaviour that surprise [#behaviour-that-surprise]

* `LogSegment.open` in `MAPPED` mode results in a file that appears to be its full `capacity` in terms of disk usage/file size, even if it only contains one small record (`ModeMigrationTest.kt:76-85`).
* `LogSegment.transferTo` is allowed to return fewer bytes than requested, meaning the caller must loop until the entire transfer is complete (`LogSegment.kt:154-156`).
* The `nextOffset` is updated **after** the index and writer have been updated, ensuring that an offset is only visible to readers once its corresponding bytes are safely written (`LogSegment.kt:77-78`).
