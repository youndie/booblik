# FetchDecodeBenchmark and the JVM reader cost (/wiki/booblik-benchmark/fetchdecodebenchmark-and-the)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

This module provides a performance measurement suite to resolve a specific architectural conflict: whether using a shared, multiplatform decoder for the `FETCH` response imposes a performance penalty on JVM clients compared to a specialized, JVM-only reader.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant B as Broker (Bytes)
    participant R as ResponseReader (JVM)
    participant D as ResponseDecoder (Shared)
    participant C as Client (FetchResult)

    Note over R, D: The &#x22;Dilemma&#x22;
    B->>R: ByteBuffer (JVM-only)
    R->>D: ResponseDecoder.fetch(ByteBuffer)
    Note right of D: Checksum via CRC32C Intrinsic
    D->>C: FetchResult (Mapped)

    B->>D: ByteArray (Multiplatform)
    D->>C: FetchResponse (Protocol level)"
/>

## The Fetch decoder dilemma [#the-fetch-decoder-dilemma]

The architectural conflict arises from the need for checksum verification. Because the broker streams segment bytes to the socket without looking at them (zero-copy), the client is the only party that can verify the data. This creates a tension between two approaches: a `ResponseReader` optimized for the JVM and a `ResponseDecoder` designed for multiplatform compatibility. As noted in [`FetchDecodeBenchmark.kt:19-31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L19-L31), the decision to merge these paths was deferred until measurements could prove whether the shared decoder's abstraction would cost the JVM reader performance.

## ResponseReader vs ResponseDecoder [#responsereader-vs-responsedecoder]

The benchmark compares two distinct paths:

* **`reader()`**: Uses `ResponseReader.fetch` over `ByteBuffer`, which is a JVM-only implementation ([`FetchDecodeBenchmark.kt:74`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L74)).
* **`decoder()`**: Uses `ResponseDecoder.fetch` over `ByteArray` and index arithmetic, which is shared with Kotlin/Native ([`FetchDecodeBenchmark.kt:24-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L24-L25)).

The core of the comparison is whether the `CRC32C` intrinsic on the JVM is efficient enough to offset the overhead of the shared decoder's abstraction.

## RecordSize and throughput scaling [#recordsize-and-throughput-scaling]

The `recordSize` parameter is used to isolate the cost of bookkeeping versus the cost of checksumming. The benchmark tests three specific sizes:

| recordSize | Impact                                                                                                                                                                                                                                                         |
| ---------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 64 B       | Bookkeeping is as visible as it ever gets ([`FetchDecodeBenchmark.kt:33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L33)) |
| 1024 B     | Intermediate scaling                                                                                                                                                                                                                                           |
| 8192 B     | Checksum dominates the execution time ([`FetchDecodeBenchmark.kt:33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L33))     |

## CorruptRecordException and offset mapping [#corruptrecordexception-and-offset-mapping]

When a checksum mismatch is detected, the protocol-level `CorruptRecordException` contains an offset relative to the start of the fetch. The `ResponseReader` is responsible for translating this into a client-level exception. Specifically, it catches the protocol exception and re-throws a client-side `CorruptRecordException` where the offset is adjusted to be relative to the fetch, ensuring the caller receives a consistent API ([`ResponseReader.kt:93-99`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L93-L99)).

## Truncated response handling [#truncated-response-handling]

The decoder must distinguish between different types of incomplete data to prevent false alarms. The `fetch` logic handles two specific edge cases:

1. **Truncated Payload**: If the `size` of a record is greater than the remaining bytes in the buffer, it returns a `FetchResponse` with `truncated = true` and the `truncatedRecordBytes` set to the expected size ([`Responses.kt:129-137`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Responses.kt#L129-L137)).
2. **Truncated Header**: If the response ends while reading a record header (fewer bytes left than `Protocol.RECORD_HEADER_BYTES`), the response is marked as truncated, but the `truncatedRecordBytes` remains zero because the size could not be determined ([`Responses.kt:152`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Responses.kt#L152)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                            | Lines    | What is there                                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | -------------------------------------------------------------------------------- |
| [`…/benchmark/FetchDecodeBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L40-L78 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt") | `40-78`  | The benchmark state, setup logic, and the two target benchmark methods.          |
| [`…/client/ResponseReader.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L90-L108 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt")                         | `90-108` | The `fetch` implementation that maps protocol results to client results.         |
| [`…/wire/Responses.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Responses.kt#L96-L154 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Responses.kt")                              | `96-154` | The core `fetch` logic including checksum verification and truncation detection. |

## Behaviour that surprises [#behaviour-that-surprises]

* `ResponseDecoder.fetch` uses a `check(promised == reader.remaining)` to ensure the frame length matches the promised payload, which serves as a consistency check for the log state ([`Responses.kt:114`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Responses.kt#L114)).
* The `ResponseReader.fetch` method performs a translation of exceptions rather than a direct propagation to maintain the library's public API contract ([`ResponseReader.kt:94-99`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L94-L99)).
