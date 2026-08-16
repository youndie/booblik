# Atomic offset persistence (/wiki/dev-common/atomic-offset-persistence)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer/Relay
    participant F as FileOffsetStore
    participant OS as OS/Filesystem
    participant L as LogSegment

    C->>F: save(topic, partition, offset)
    F->>OS: write to .tmp file
    F->>OS: atomic move (.tmp -> .offset)
    Note over OS: Atomic move prevents partial writes
    
    L->>L: append(payload)
    L->>L: write record + checksum
    Note over L: M-60: Checksum protects body
    
    C->>L: read(offset)
    L->>L: verify checksum
    alt Checksum Mismatch
        L-->>C: throw CorruptRecordException
    else Success
        L-->>C: return bytes
    end"
/>

## FileOffsetStore [#fileoffsetstore]

The mechanism for persisting consumer positions using atomic file moves to prevent corruption during crashes. As described in [`FileOffsetStore.kt:23-25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L23-L25), a position saved halfway is considered worse than no position at all, as a truncated offset like `"1"` parsed from `"12"` would cause silent replays. To prevent this, the `save` method writes to a temporary file and uses `StandardCopyOption.ATOMIC_MOVE` to ensure the offset is updated completely or not at all [`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57).

## The write-ahead-log and SegmentMode [#the-write-ahead-log-and-segmentmode]

The system provides two distinct write paths for log segments, which can be selected via the `SegmentMode` enum [`LogSegment.kt:25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L25):

| Mode           | Description                                |
| -------------- | ------------------------------------------ |
| `FILE_CHANNEL` | Uses a standard `FileChannel` for writing. |
| `MAPPED`       | Uses memory mapping for the write path.    |

The `MAPPED` mode is the default and is chosen based on performance measurements [`LogSegment.kt:19-20`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L19-L20). While `MAPPED` can be faster, it can suffer from performance degradation when the writer outruns the OS writeback, a phenomenon explored in [`SustainedWriteProbe.kt:18-19`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L18-L19).

## Recovery after a crash [#recovery-after-a-crash]

When a `LogSegment` is opened, it must undergo a recovery process to rebuild the `SparseOffsetIndex` by walking the record headers [`LogSegment.kt:253-254`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L253-L254). The recovery mechanism reads headers in chunks using a `RECOVERY_BUFFER` to avoid excessive syscalls [`LogSegment.kt:327-330`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L327-L330). Because recovery relies on the length prefix, a record whose declared length extends beyond the file boundary is discarded, and the segment is truncated to the last intact boundary [`LogSegment.kt:302-304`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L302-L304).

## Corruption detection and checksums [#corruption-detection-and-checksums]

To address the risk where a torn write inside a record body is not detectable by length prefixes alone, the system implements checksums (M-60) [`LogSegment.kt:258-259`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L258-L259). During recovery, the `verify` function checks the record's body against its stored CRC [`LogSegment.kt:405`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L405). If a mismatch is detected, the recovery process stops to prevent the system from treating garbage as valid data [`LogSegment.kt:364-366`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L364-L366).

## CorruptionTest [#corruptiontest]

The `CorruptionTest` class validates that the system correctly identifies and reacts to data corruption [`CorruptionTest.kt:23`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt#L23). Key test cases include:

* **Bit-flipping**: Ensuring a single bit flip in a record body is caught [`CorruptionTest.kt:50-51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt#L50-L51).
* **Torn bodies**: Verifying that even if a length prefix survives, a modified body is detected [`CorruptionTest.kt:108-109`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt#L108-L109).
* **Recovery boundaries**: Ensuring that once a bad record is encountered, the system stops and does not allow subsequent records to be incorrectly processed [`CorruptionTest.kt:64-65`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt#L64-L65).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines     | What is there                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---------------------------------------------------------- |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27-L29 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `27-29`   | The `FileOffsetStore` class for atomic offset persistence. |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L24-L25 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")                 | `24-25`   | The `SegmentMode` enumeration.                             |
| [`…/storage/LogSegment.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L261-L267 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt")               | `261-267` | The `open` function for initializing a segment.            |
| [`…/storage/CorruptionTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt#L49-L51 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CorruptionTest.kt")     | `49-51`   | Test case for bit-flipping detection.                      |

## Behaviour that surprising [#behaviour-that-surprising]

* The `LogSegment.open` function performs a `recover` operation that skips record bodies, only reading headers to rebuild the index [`LogSegment.kt:319`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/LogSegment.kt#L319).
* In `FileOffsetStore`, the `save` operation is designed to be "all or nothing" by using a temporary file and an atomic move to prevent partial writes of the offset [`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57).
* The `SustainedWriteProbe` is designed to intentionally saturate the OS writeback to observe the performance degradation of the `MAPPED` mode [`SustainedWriteProbe.kt:32-33`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L32-L33).
