# Recovery after a crash (/wiki/booblik-core/recovery-after-crash)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant OS as Operating System / Disk
    participant SC as LogSegment.open
    participant REC as recover()
    participant IDX as SparseOffsetIndex
    participant WR as SegmentWriter

    SC->>REC: Start recovery
    loop Scan headers
        REC->>OS: Read header (length + CRC)
        alt Valid Record
            REC->>IDX: append(offset, position, size)
            REC->>REC: Advance position to end of body
        else Invalid/Torn Record
            REC->>REC: Stop recovery
        end
    end
    REC->>WR: truncateTo(recoveredPosition)
    SC->>SC: Return LogSegment"
/>

## The recovery mechanism [#the-recovery-mechanism]

When a segment is opened, `LogSegment.open` invokes the `recover` function to reconstruct the state of the log from the raw bytes on disk ([`LogSegment.kt:261-373`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L261-L373)). This process involves walking the file by reading record headers in chunks (using a 1 MiB `RECOVERY_BUFFER` as seen in [`LogSegment.kt:327`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L327)) to find valid record boundaries. As the function traverses the file, it populates the `SparseOffsetIndex` by calling `index.append` for each valid record found ([`LogSegment.kt:368`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L368)).

## SegmentMode and the end-of-log sentinel [#segmentmode-and-the-end-of-log-sentinel]

The definition of the "end of the log" depends on the `SegmentMode` used, which determines how the `limit` for recovery is calculated ([`LogSegment.kt:285`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L285)):

| Mode           | End-of-log Sentinel                             |
| -------------- | ----------------------------------------------- |
| `FILE_CHANNEL` | The physical file length (`readChannel.size()`) |
| `MAPPED`       | A zero-length prefix in a pre-sized file        |

In `MAPPED` mode, because the file is pre-sized to its full capacity ([`LogSegment.kt:288`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L288)), the recovery process must rely on finding a zero length or a checksum mismatch to stop, rather than the file size ([`LogSegment.kt:349`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L349)).

## Torn records and checksum verification [#torn-records-and-checksum-verification]

To handle crashes that occur during a write, the recovery process uses CRC32C checksums stored in the `RECORD_HEADER` ([`SegmentWriter.kt:85`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L85)). During recovery, the `verify` function checks the payload against the expected CRC ([`LogSegment.kt:405`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L405)). If a record's body was only partially written, the checksum will fail, and recovery will treat that point as the end of the log. However, there is a known gap: if a torn write occurs *inside* a record body, it is only detectable if the checksum is verified; without it, the length prefix alone is the only evidence of integrity ([`LogSegment.kt:258`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L258)).

## SparseOffsetIndex reconstruction [#sparseoffsetindex-reconstruction]

The `SparseOffsetIndex` is rebuilt by scanning the log and adding entries only when the `intervalBytes` threshold is crossed ([`SparseOffsetIndex.kt:73`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L73)). During recovery or manual truncation, the index is updated via `truncateTo`, which discards all entries at or above the specified offset to ensure the index does not describe data that no longer exists in the segment ([`SparseOffsetIndex.kt:103`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L103)).

## CrashRecoveryTest invariants [#crashrecoverytest-invariants]

The `CrashRecoveryTest` ensures that the system remains consistent even after a `SIGKILL`. It spawns a `CrashWriter` as a separate subprocess to simulate a hard crash ([`CrashRecoveryTest.kt:56`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CrashRecoveryTest.kt#L56)). The core invariant verified is that after reopening the segment, the log must end on a valid record boundary, meaning every reported record is readable and matches its original checksum ([`CrashRecoveryTest.kt:106`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CrashRecoveryTest.kt#L106)).

## RecoveryTest edge cases [#recoverytest-edge-cases]

The `RecoveryTest` suite validates several critical recovery scenarios:

* **Offset Restoration:** Ensuring that reopening a segment restores the `nextOffset` and the index correctly ([`RecoveryTest.kt:38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L38)).
* **MAPPED Mode Orphans:** In `MAPPED` mode, if a crash leaves an "orphaned body" (data written but the header not yet reached), the recovery mechanism must correctly discard it by treating the missing header as the end of the log ([`RecoveryTest.kt:98`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L98)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                     | Lines     | What is there                                   |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------- |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L321-L373 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")                      | `321-373` | The `recover` function implementation           |
| [`…/storage/SparseOffsetIndex.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L71-L85 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt")   | `71-85`   | Logic for appending entries to the sparse index |
| [`…/storage/SegmentWriter.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L85-L86 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt")               | `85-86`   | Definition of the `RECORD_HEADER`               |
| [`…/storage/CrashRecoveryTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CrashRecoveryTest.kt#L134-L163 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CrashRecoveryTest.kt") | `134-163` | The `CrashWriter` subprocess implementation     |
| [`…/storage/RecoveryTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L87-L106 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt")                 | `87-106`  | Tests for `MAPPED` mode edge cases              |

## Behaviour that surprises [#behaviour-that-surprises]

* `LogSegment.open` in `MAPPED` mode may pre-size the file to its full capacity, meaning the file size on disk does not represent the amount of data actually written ([`LogSegment.kt:282`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L282)).
* The `SparseOffsetIndex` is not dense; it only stores an entry every `intervalBytes`, requiring a forward scan to find exact positions ([`SparseOffsetIndex.kt:18`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L18)).
* `SegmentWriter.append` is not thread-safe by design; it assumes a single-threaded ownership by a single coroutine ([`SegmentWriter.kt:15`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L15)).
