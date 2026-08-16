# PartitionLog (/wiki/booblik-core/partitionlog)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

`PartitionLog` is the core storage component of the `booblik` system, responsible for managing an ordered sequence of `LogSegment` objects. It provides an append-only log abstraction where data is partitioned into segments to facilitate efficient retention (deletion of old data) and high-performance reads.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant W as Writer (Single Coroutine)
    participant P as PartitionLog
    participant S as Active LogSegment
    participant R as Reader (Multiple)
    participant T as Retention (Background)

    W->>P: append(payload)
    alt No Room
        P->>P: roll()
        P->>S: close()
        P->>P: create new LogSegment
    end
    P->>S: append(payload)
    
    R->>P: segmentFor(offset)
    P->>S: acquire()
    S-->>R: FetchSlice
    R->>S: transferTo()
    R->>S: release()

    T->>P: retainAtMost(maxBytes)
    P->>P: update segments list (immutable)
    P->>S: retire()
    Note over S: File unlinked (POSIX)
    S-->>R: continues reading until last descriptor closed"
/>

## PartitionLog mechanics [#partitionlog-mechanics]

The `PartitionLog` architecture is built on a single-writer, multi-reader concurrency model. To avoid the overhead of locks on the hot path, the segment list is maintained as an immutable `List` behind a `@Volatile` field, which is replaced rather than mutated whenever the log structure changes (e.g., during a `roll` or retention) [`PartitionLog.kt:41-42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L41-L42). This ensures that readers always see a consistent snapshot of the log, either before or after a change, but never a partially mutated list [`PartitionLog.kt:19-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L19-L22).

## LogSegment lifecycle and retention [#logsegment-lifecycle-and-retention]

Segments follow a strict lifecycle to ensure data is not deleted while being actively read. Readers must use `acquire()` to claim a segment, which prevents the retention process from closing the segment's descriptors until the reader calls `release()` [`PartitionLog.kt:111-116`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L111-L116).

Retention is performed at the segment level via `retainAtMost` or `retainNewerThan`, which unlinks files from the filesystem to reclaim space [`PartitionLog.kt:185-195`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L185-L195). A key safety mechanism is that a segment is removed from the volatile `segments` list *before* `retire()` is called, ensuring new readers cannot find it, while existing readers can continue streaming data from the unlinked file until they release their handle [`PartitionLog.kt:27-31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L27-L31).

## SegmentMode and zero-copy transfer [#segmentmode-and-zero-copy-transfer]

The log supports different modes for data persistence and access:

| Mode           | Description                                  |
| -------------- | -------------------------------------------- |
| `MAPPED`       | Uses memory-mapped files for segment access. |
| `FILE_CHANNEL` | Uses standard `FileChannel` operations.      |

For high-performance data movement, `transferTo` utilizes `FileChannel.transferTo` to move bytes directly from the file cache to a `WritableByteChannel` without copying data through the JVM heap [`PartitionLog.kt:173-175`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L173-L175).

## Data integrity and recovery properties [#data-integrity-and-recovery-properties]

The log ensures consistency across restarts by scanning the partition directory and rebuilding the segment list from existing `.log` files [`PartitionLog.kt:238-245`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L238-L245). The `LogPropertiesTest` verifies that a reopened log recovers exactly the same records and offsets as the original session [`LogPropertiesTest.kt:97-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/LogPropertiesTest.kt#L97-L103). Additionally, the `truncateTo` operation on a `LogSegment` allows for precise data removal, ensuring that the log remains consistent by reusing the freed offset for subsequent appends [`LogPropertiesTest.kt:147-151`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/LogPropertiesTest.kt#L147-L151).

## PartitionLog boundary and error invariants [#partitionlog-boundary-and-error-invariants]

The implementation enforces several strict invariants to prevent corruption and resource leaks:

* **Record Size Limits:** A record that is larger than an empty segment is rejected immediately to prevent the creation of "stray" empty files [`PartitionLog.kt:64-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L64-L65).
* **Segment Rolling:** The log automatically rolls to a new segment when the current `activeSegment` cannot accommodate a new record [`PartitionLog.kt:75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L75).
* **Boundary Integrity:** Tests confirm that records are readable even when they span across segment boundaries and that `transferTo` never attempts to cross a segment boundary in a single call [`PartitionLogTest.kt:84-96`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/PartitionLogTest.kt#L84-L96).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                         | Lines     | What is there                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------- |
| [`…/storage/PartitionLog.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L33-L40 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt")                                                      | `33-40`   | Primary `PartitionLog` class definition and constructor |
| [`…/storage/PartitionLog.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L132-L138 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt")                                                    | `132-138` | `FetchSlice` inner class for managing segment claims    |
| [`…/storage/PartitionLogTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/PartitionLogTest.kt#L32-L48 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/PartitionLogTest.kt")                                          | `32-48`   | Tests for segment rolling and offset accuracy           |
| [`…/storage/LogPropertiesTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/LogPropertiesTest.kt#L43-L80 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/LogPropertiesTest.kt")                                       | `43-80`   | Randomized property-based testing for data integrity    |
| [`…/benchmark/PartitionWriterBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L92-L102 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt") | `92-102`  | Benchmark for append performance and retention overhead |

## Behaviour that surprising [#behaviour-that-surprising]

* `PartitionLog.append` will throw an `IllegalArgumentException` if a record is too large for a segment, rather than attempting to roll a new segment first [`PartitionLog.kt:72-74`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L72-L74).
* When a segment is unlinked via `retire`, a reader holding a `FetchSlice` can continue to read the data until the slice is closed, thanks to POSIX file descriptor behavior [`PartitionLog.kt:28-30`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/PartitionLog.kt#L28-L30).
* `PartitionLog.open` performs a directory scan to recover state, which can be a significant startup cost for logs with many segments [`StartupProbe.kt:42-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/StartupProbe.kt#L42-L48).
