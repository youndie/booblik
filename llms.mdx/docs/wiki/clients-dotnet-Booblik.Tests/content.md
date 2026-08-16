# clients/dotnet/Booblik.Tests (/wiki/clients-dotnet-Booblik.Tests)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

(Documentation for the Booblik.Tests module, covering the testing infrastructure and validation logic for the Booblik client.)

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant T as Test Case
    participant FB as FakeBroker
    participant C as Connection/Client
    
    T->>FB: Seed data / Configure Refusal
    T->>C: Call ProduceAsync / FetchAsync / PollAsync
    C->>FB: Send Request (TCP)
    FB->>FB: Decode & Process (Produce/Fetch/Metadata)
    FB-->>C: Send Response (TCP)
    C->>T: Return Result / Throw Exception"
/>

## FakeBroker [#fakebroker]

The mock broker implementation used to simulate network responses, protocol framing, and broker-side errors. It implements a `TcpListener` to handle real network communication on a loopback address [`FakeBroker.cs:57-169`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L57-L169). The broker's behavior can be configured via:

| Property     | Type   | Purpose                                                                                                                                                                                                            |
| ------------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Corrupt`    | `bool` | Flips a bit in stored checksums to simulate damaged segments [`FakeBroker.cs:36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L36) |
| `RefuseWith` | `Code` | Sets the error code for the next response [`FakeBroker.cs:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L60)                    |

The broker maintains state for `_produced` records [`FakeBroker.cs:26`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L26) and tracks the `LastFetch` metadata [`FakeBroker.cs:41-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L41-L46).

More: [FakeBroker](clients-dotnet-Booblik.Tests/fakebroker)

## Connection [#connection]

Validation of the low-level transport, including byte-for-byte payload integrity, protocol error handling, and metadata routing. Tests ensure that `ARecordArrivesByteForByte` preserves all byte values including nulls [`ConnectionTests.cs:17-31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L17-L31), and that `ATruncatedResponseIsAProtocolError` correctly identifies malformed frames [`ConnectionTests.cs:100-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L100-L103). It also validates that `MetadataAndKeyRouting` correctly interacts with the partitioner [`ConnectionTests.cs:68-79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L68-L79).

More: [Connection](clients-dotnet-Booblik.Tests/connection)

## Consumer [#consumer]

Testing of the consumer lifecycle, including record ordering, high watermark behavior, max bytes truncation, and checksum validation. The consumer is tested against "golden vectors" for CRC32C checksums [`ConsumerTests.cs:38-47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L38-L47). Key behaviors tested include:

* `RecordsComeBackInOrder`: Verifies position and high watermark updates [`ConsumerTests.cs:60-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L60-L75).
* `TruncatedTailIsDroppedAndRefetched`: Ensures `MaxBytes` cuts on byte boundaries do not corrupt data [`ConsumerTests.cs:100-113`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L100-L113).
* `RecordLargerThanMaxBytesIsReported`: Validates `RecordExceedsMaxBytesException` when a single record exceeds the buffer [`ConsumerTests.cs:136-150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L136-L150).

## Producer [#producer]

Verification of batching logic, linger windows, partition accumulation, and asynchronous error propagation. The producer is tested to ensure `NoRecordsAreLostAcrossLingerWindows` by simulating timing-sensitive interleaving [`ProducerTests.cs:17-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L17-L51). Other verified behaviors include:

* `PartitionsAccumulateSeparately`: Ensures batches are partitioned by their target [`ProducerTests.cs:73-95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L73-L95).
* `DisposeFlushesWhatIsQueued`: Verifies that `DisposeAsync` triggers a final flush of pending records [`ProducerTests.cs:97-117`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L97-L117).
* `ABrokerFailureReachesEveryWaitingCaller`: Ensures all tasks in a batch receive the `BrokerException` [`ProducerTests.cs:141-157`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L141-L157).

## Partitioner [#partitioner]

Validation of the FNV-1a hashing algorithm and partition assignment against golden vectors. The tests ensure `MatchesTheGoldenVectors` by comparing results against a known-good implementation [`PartitionerTests.cs:20-39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/PartitionerTests.cs#L20-L39). It specifically validates that `HighBytesAreUnsigned` to prevent errors common in signed-integer ports [`PartitionerTests.cs:47-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/PartitionerTests.cs#L47-L51).

## Key files [#key-files]

| File                                                                                                                                                                                                                                     | Lines     | What is there                                           |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------- |
| [`…/Booblik.Tests/Booblik.Tests.csproj`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/Booblik.Tests.csproj#L13-L17 "clients/dotnet/Booblik.Tests/Booblik.Tests.csproj") | `13-17`   | Test dependencies including xUnit and Coverlet          |
| [`…/Booblik.Tests/ConnectionTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L17-L31 "clients/dotnet/Booblik.Tests/ConnectionTests.cs")       | `17-31`   | Byte-for-byte payload integrity test                    |
| [`…/Booblik.Tests/ConsumerTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L38-L47 "clients/dotnet/Booblik.Tests/ConsumerTests.cs")             | `38-47`   | CRC32C checksum validation against golden vectors       |
| [`…/Booblik.Tests/FakeBroker.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L175-L227 "clients/dotnet/Booblik.Tests/FakeBroker.cs")                    | `175-227` | The `Produce` method implementation for the mock broker |
| [`…/Booblik.Tests/PartitionerTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/PartitionerTests.cs#L20-L39 "clients/dotnet/Booblik.Tests/PartitionerTests.cs")    | `20-39`   | FNV-1a golden vector validation                         |
| [`…/Booblik.Tests/ProducerTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L17-L51 "clients/dotnet/Booblik.Tests/ProducerTests.cs")             | `17-51`   | Linger window and record loss regression test           |

## Behaviour that does not surprise [#behaviour-that-does-not-surprise]

* `Producer.SendAsync` returns `Producer.OffsetUnknown` when `AckPolicy.None` is used, rather than waiting for a broker response [`ProducerTests.cs:132-134`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ProducerTests.cs#L132-L134).
* `Consumer.PollAsync` will throw a `RecordExceedsMaxBytesException` if a single record is larger than the configured `MaxBytes`, preventing an infinite retry loop [`ConsumerTests.cs:145-150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConsumerTests.cs#L145-L150).
* `FakeBroker` uses a `lock` on `_gate` to ensure thread-safe access to the `_produced` dictionary and `_nextOffset` [`FakeBroker.cs:25-28`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L25-L28).
