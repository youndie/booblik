# ProduceAsync and AckPolicy (/wiki/clients-dotnet-Booblik/produceasync-and-ackpolicy)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (ProduceAsync)
    participant B as Broker
    participant L as Partition Log

    C->>B: Send Batch (Topic, Partition, Records, AckPolicy)
    alt AckPolicy.None
        B--x C: No Response
    else AckPolicy.Written
        B->>L: Append Records
        L-->>B: Success (LogEndOffset)
        B-->>C: ProduceResult (BaseOffset, LogEndOffset)
    else AckPolicy.Forced
        B->>L: Append Records
        B->>L: Force Sync (fsync)
        L-->>B: Success
        B-->>C: ProduceResult (BaseOffset, LogEndOffset)
    end"
/>

## AckPolicy [#ackpolicy]

The `AckPolicy` enumeration defines how the client waits for confirmation from the broker:

| Mode      | Value | Description                                                                      |
| --------- | ----- | -------------------------------------------------------------------------------- |
| `None`    | 0     | The broker answers nothing; the client receives no response and no offset.       |
| `Written` | 1     | The broker answers once the record is in the log, before any durability barrier. |
| `Forced`  | 2     | The broker answers after a `force()` operation (grouping all queued requests).   |

[`Connection.cs:8-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L8-L22)

## ProduceAsync mechanics [#produceasync-mechanics]

The `ProduceAsync` method constructs a binary payload containing the topic name, partition ID, the chosen `AckPolicy`, and the list of records. The records are written to the partition such that they land contiguously, meaning one request results in a sequence of offsets from `BaseOffset` to `LogEndOffset` with no interleaving from other requests [`Connection.cs:183-213`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L183-L213).

## The write actor and batching [#the-write-actor-and-batching]

Performance is heavily dependent on how records are grouped. While `Topic.SendAsync` allows sending a single record, doing so one at a time is inefficient compared to using `Connection.ProduceAsync` with a list of records [`Connection.cs:382-387`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L382-L387). In the Kotlin implementation, the `batch` function provides a way to collect records into a `BatchScope` to ensure they are sent as a single request, bypassing the standard accumulator to maintain the guarantee that records land contiguously [`Publishing.kt:85-99`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L85-L99).

## Failure modes and BrokerException [#failure-modes-and-brokerexception]

The client handles various broker responses. If a batch is empty or contains empty records, the broker may return a `CORRUPT_REQUEST` [`Connection.cs:178-180`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L178-L180). If the broker refuses a request (e.g., `UNKNOWN_TOPIC_OR_PARTITION`), a `BrokerException` is thrown to the caller [`Connection.cs:131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L131). Under `AckPolicy.None`, `ProduceAsync` returns `null` because no response is expected from the broker [`Connection.cs:214-216`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L214-L216).

## Recovery after a crash [#recovery-after-a-crash]

Writes are not atomic. If a crash occurs mid-write, the system is designed so that recovery keeps the prefix of the batch that passed its checksums, meaning a partial batch can survive on its own [`Publishing.kt:76-79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L76-L79).

## Connection integrity tests [#connection-integrity-tests]

The test suite ensures high reliability through several checks:

* **Byte-for-byte delivery**: Verifying that records arrive exactly as sent [`ConnectionTests.cs:24-30`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L24-L30).
* **Error propagation**: Ensuring that a broker refusal is treated as a `BrokerException` but does not close the connection, allowing for reuse [`ConnectionTests.cs:56-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L56-L64).
* **Protocol integrity**: Confirming that a truncated response (e.g., due to a broker restart) results in a `ProtocolException` [`ConnectionTests.cs:100-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L100-L103).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                         | Lines     | What is there                                               |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------------- |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L8-L22 "clients/dotnet/Booblik/Connection.cs")                                                                                                                                             | `8-22`    | `AckPolicy` enumeration definition                          |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L183-L223 "clients/dotnet/Booblik/Connection.cs")                                                                                                                                          | `183-223` | `ProduceAsync` implementation and payload construction      |
| [`…/client/Publishing.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L85-L99 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt")                                                   | `85-99`   | `Producer.batch` implementation for contiguous writes       |
| [`…/Booblik.Tests/ConnectionTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L24-L31 "clients/dotnet/Booblik.Tests/ConnectionTests.cs")                                                                                                           | `24-31`   | Tests for byte-for-byte record delivery                     |
| [`…/Booblik.Tests/ConnectionTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L56-L64 "clients/dotnet/Booblik.Tests/ConnectionTests.cs")                                                                                                           | `56-64`   | Tests for error propagation and connection reuse            |
| [`…/Booblik.Tests/ConnectionTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L100-L103 "clients/dotnet/Booblik.Tests/ConnectionTests.cs")                                                                                                         | `100-103` | Tests for truncated response handling                       |
| [`…/benchmark/PartitionWriterBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L92-L102 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt") | `92-102`  | Benchmark for append performance and retention              |
| [`…/Booblik.Conformance/Program.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L127-L138 "clients/dotnet/Booblik.Conformance/Program.cs")                                                                                                               | `127-138` | Conformance testing for `Produce` verb and `AckPolicy.None` |

## Behaviour that not obvious [#behaviour-that-not-obvious]

* `Connection.ProduceAsync` returns `null` when `AckPolicy.None` is used, which can be interpreted as "no answer is coming" [`Connection.cs:214-216`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L214-L216).
* A `BrokerException` does not necessarily close the `Connection`; if the framing remains intact, the connection remains usable for subsequent requests [`ConnectionTests.cs:60-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L60-L64).
* `Topic.SendAsync` is a convenience method that wraps a single record into a list, which is significantly slower than batching multiple records in one `ProduceAsync` call [`Connection.cs:382-387`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L382-L387).
