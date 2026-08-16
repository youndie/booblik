# The partitioner and checksum algorithms (/wiki/conformance/the-partitioner-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Producer (Client)&#x22;
        K[Key] --> P[Partitioner]
        P --> |Partition ID| B[Broker]
        PL[Payload] --> C[CRC32C]
        C --> |Checksum| B
    end
    subgraph &#x22;Broker (Storage)&#x22;
        B --> SW[SegmentWriter]
        SW --> |Append| S[Segment File]
    end
    subgraph &#x22;Consumer (Client)&#x22;
        S --> |Zero-copy Stream| RR[ResponseReader]
        RR --> |Verify| C
        C --> |Match?| V{Valid?}
        V -->|No| E[CorruptRecordException]
    end"
/>

## The partitioner [#the-partitioner]

Mechanics of key distribution and the divergence between FNV-1a and Java-style hashing.

The partitioner determines which partition a record is assigned to based on its key. Because the key never reaches the broker—the client picks the partition and sends the number—any divergence in how two clients hash the same key results in data being placed in different partitions, breaking per-key ordering. This is a silent failure that the conformance suite is designed to catch [`README.md:15-17`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/README.md#L15-L17).

## The `fnv1a32` and `partition_fnv1a` algorithms [#the-fnv1a32-and-partition_fnv1a-algorithms]

Implementation of unsigned 32-bit FNV-1a and its unsigned remainder folding.

The `fnv1a32` function implements FNV-1a over unsigned bytes using a specific offset basis and prime [`algorithms.py:30-33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L30-L33). The `partition_fnv1a` function then performs an unsigned remainder operation to map the hash to a partition count [`algorithms.py:63`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L63).

## The `java_array_hash` and `partition_java_array_hash` algorithms [#the-java_array_hash-and-partition_java_array_hash-algorithms]

Mechanics of signed byte summation and `Math.floorMod` folding, including the 0x80 sign-extension edge case.

The `java_array_hash` function mimics `java.util.Arrays.hashCode(byte[])` by treating bytes as signed values; specifically, a byte like `0x80` enters the sum as `-128` rather than `128` [`algorithms.py:39-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L39-L45). The `partition_java_array_hash` function uses `to_signed32` to ensure the hash is treated as a signed 32-bit integer before applying the modulo operator, which behaves like `Math.floorMod` for positive divisors [`algorithms.py:68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L68).

## The `crc32c` checksum [#the-crc32c-checksum]

Castagnoli polynomial implementation and the distinction from standard CRC-32.

The `crc32c` function implements the Castagnoli polynomial (`0x1EDC6F41`) using a reflected table implementation [`algorithms.py:52-54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L52-L54). It is explicitly noted that this is not `zlib.crc32` or other common CRC-32 implementations, as they use different polynomials [`algorithms.py:50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L50).

## The `SegmentWriter` checksum verification [#the-segmentwriter-checksum-verification]

The role of CRC32C in protecting the log against torn records during recovery.

In the `SegmentWriter` interface, every record is framed with a `CRC_BYTES` length checksum [`SegmentWriter.kt:82`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L82). This checksum is used during recovery to detect "torn" records—data that was only partially written when a process died—by verifying that the bytes match the header [`SegmentWriter.kt:71-73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L71-L73).

## The `ResponseReader` checksum verification [#the-responsereader-checksum-verification]

Client-side verification of records on the zero-copy read path and `CorruptRecordException` handling.

Because the broker uses zero-copy to stream bytes directly to the socket, the client is the only party that can verify the data integrity [`ResponseReader.kt:36-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L36-L37). If a record's checksum fails during unpacking, the `ResponseReader` catches the protocol-level error and throws a `CorruptRecordException` [`ResponseReader.kt:94-99`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L94-L99).

## The conformance vectors [#the-conformance-vectors]

Testing edge cases like non-ASCII keys, high bytes (0x80), and empty payloads.

The conformance suite uses specific keys to catch common implementation errors, such as `0x80` which is the first byte where signed and unsigned readings diverge [`generate.py:48-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/vectors/generate.py#L48-L49). It also tests various payload types including empty payloads, high bytes, and UTF-8 sequences like emoji or Cyrillic [`generate.py:64-73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/vectors/generate.py#L64-L73).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                   | Lines     | What is there                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ---------------------------------------------------------------- |
| [`…/harness/algorithms.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L28-L68 "conformance/harness/algorithms.py")                                                                                                            | `28-68`   | Implementations of FNV-1a, Java-style array hashing, and CRC32C. |
| [`…/storage/SegmentWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L88-L96 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt")             | `88-96`   | The `checksum` companion function for calculating CRC32C.        |
| [`…/vectors/generate.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/vectors/generate.py#L117-L120 "conformance/vectors/generate.py")                                                                                                                | `117-120` | Generation of CRC32C vectors for testing.                        |
| [`…/client/ResponseReader.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L39-L42 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt") | `39-42`   | Definition of `CorruptRecordException`.                          |

## Behaviour that surprise [#behaviour-that-surprise]

* A client that fails to verify checksums on the read path silently disables the project's only defense against log corruption [`README.md:18-20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/README.md#L18-L20).
* The `ResponseReader` might throw a `CorruptRecordException` with an offset that is relative to the start of the fetch response, not the absolute log position [`ResponseReader.kt:96-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L96-L97).
* An "absurd" frame length (e.g., `Int.MAX_VALUE`) is handled by dropping the connection rather than allowing the broker to attempt a massive allocation [`PartialFrameTest.kt:112-118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L112-L118).
