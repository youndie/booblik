# clients/dotnet/Booblik (/wiki/clients-dotnet-Booblik)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (Producer/Consumer)
    participant Conn as Connection
    participant B as Broker

    C->>Conn: ConnectAsync(address)
    Conn->>B: TCP Handshake
    B-->>Conn: Established
    
    Note over C,B: Metadata Discovery
    C->>Conn: MetadataAsync(topics)
    Conn->>B: ApiMetadata (v1)
    B-->>Conn: Topic/Partition Info
    Conn-->>C: Dictionary<string, List<PartitionInfo>>

    Note over C,B: Producing Records
    C->>Conn: ProduceAsync(topic, partition, records, ack)
    Conn->>B: ApiProduce (v1)
    B-->>Conn: ProduceResult (BaseOffset, LogEndOffset)
    Conn-->>C: ProduceResult?

    Note over C,B: Consuming Records
    C->>Conn: FetchAsync(topic, partition, offset)
    Conn->>B: ApiFetch (v2)
    B-->>Conn: Fetched (Records + HighWatermark)
    Conn-->>C: Fetched"
/>

## Connection [#connection]

The core transport layer for communicating with the booblik broker via TCP. The `Connection` class manages a `TcpClient` and `NetworkStream` to send framed requests and receive responses [`Connection.cs:40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L40). It uses a correlation ID to match responses to requests, ensuring that even if multiple requests were sent, the caller receives the correct answer [`Connection.cs:54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L54).

More: [Connection](clients-dotnet-Booblik/connection)

## Metadata and Topic Discovery [#metadata-and-topic-discovery]

Retrieving partition information and topic existence from the broker. The `MetadataAsync` method sends a request containing a list of topic names and decodes the response into a dictionary of partition information [`Connection.cs:145`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L145). Users can also use `TopicAsync` to get a `Topic` object which encapsulates the partitions available for a specific name [`Connection.cs:292`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L292).

## ProduceAsync and AckPolicy [#produceasync-and-ackpolicy]

Appending records to a partition and managing durability guarantees via `AckPolicy`. The `ProduceAsync` method allows sending a list of records to a specific partition [`Connection.cs:183`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L183). The durability of the write is determined by the following table:

| AckPolicy | Value | Description                                                                                                                                                                                                                |
| --------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `None`    | 0     | Answers nothing; the broker may lose the record without the client knowing [`Connection.cs:15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L15) |
| `Written` | 1     | Answers once the record is in the log, before any durability barrier [`Connection.cs:18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L18)       |
| `Forced`  | 2     | Answers after the broker's `force()` operation [`Connection.cs:21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L21)                             |

More: [ProduceAsync and AckPolicy](clients-dotnet-Booblik/produceasync-and-ackpolicy)

## FetchAsync and Consumer [#fetchasync-and-consumer]

Reading records from a partition, handling truncation, and managing the consumer position. The `Consumer` class maintains a `Position` which is the offset of the next record to be read [`Consumer.cs:55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Consumer.cs#L55). The `PollAsync` method fetches records and advances the position, but it can throw a `RecordExceedsMaxBytesException` if a record is too large to fit within the `MaxBytes` limit [`Consumer.cs:94`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Consumer.cs#L94).

More: [FetchAsync and Consumer](clients-dotnet-Booblik/fetchasync-and-consumer)

## Topic Partitioning [#topic-partitioning]

Using FNV-1a hashing to map keys to specific partitions. The `Partitioner` class provides the `Fnv1a32` hash function which uses an `unchecked` context to allow arithmetic wrapping [`Partitioner.cs:33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Partitioner.cs#L33). The `PartitionFor` method then folds this hash into the range of available partitions using the modulo operator [`Partitioner.cs:54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Partitioner.cs#L54).

## Crc32C Verification [#crc32c-verification]

Checksum validation for record integrity using the Castagnoli polynomial. The `Crc32C` class implements the CRC-32C algorithm using a pre-computed table for efficiency [`Crc32C.cs:34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Crc32C.cs#L34). This is used during the `Decode` process in a `Consumer` to ensure that the bytes received match the checksum stored with the record [`Consumer.cs:214`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L214).

## Error Handling and Protocol Exceptions [#error-handling-and-protocol-exceptions]

Distinguishing between broker refusals, protocol violations, and data corruption. The client distinguishes between different failure modes:

| Exception                | Type       | Cause                                                                                                                                                                                                                       |
| ------------------------ | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BrokerException`        | Refusal    | The broker understood the request but declined it (e.g., `UnknownTopicOrPartition`) [`Errors.cs:35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Errors.cs#L35) |
| `ProtocolException`      | Violation  | The bytes on the connection do not make sense, such as a frame length out of range [`Errors.cs:42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Errors.cs#L42)  |
| `CorruptRecordException` | Corruption | A record's computed checksum does not match the stored checksum [`Errors.cs:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Errors.cs#L52)                     |

## Key files [#key-files]

| File                                                                                                                                                                                                 | Lines    | What is there                                                                        |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------ |
| [`…/Booblik/Booblik.csproj`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Booblik.csproj#L1-L33 "clients/dotnet/Booblik/Booblik.csproj")  | `1-33`   | Project configuration and dependencies                                               |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L40-L387 "clients/dotnet/Booblik/Connection.cs")   | `40-387` | The `Connection`, `AckPolicy`, `PartitionInfo`, `ProduceResult`, and `Topic` classes |
| [`…/Booblik/Consumer.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L38-L227 "clients/dotnet/Booblik/Consumer.cs")         | `38-227` | The `Consumer` class and `Fetched` record                                            |
| [`…/Booblik/Crc32C.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Crc32C.cs#L27-L65 "clients/dotnet/Booblik/Crc32C.cs")                | `27-65`  | The `Crc32C` static utility class                                                    |
| [`…/Booblik/Errors.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Errors.cs#L4-L64 "clients/dotnet/Booblik/Errors.cs")                 | `4-64`   | Error enums and custom exception types                                               |
| [`…/Booblik/Partitioner.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Partitioner.cs#L14-L57 "clients/dotnet/Booblik/Partitioner.cs") | `14-57`  | The `Partitioner` static utility class                                               |

## Behaviour that surprises [#behaviour-that-surprises]

* `Connection.SendAsync` increments a `_correlation` field to match requests and responses, meaning the connection is not safe for concurrent use [`Connection.cs:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L60).
* `Consumer.PollAsync` advances the `Position` only after a successful fetch, and if a fetch is truncated, the partial record is dropped and the next poll starts from the beginning of that record [`Consumer.cs:106`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Consumer.cs#L106).
* `Partitioner.PartitionFor` will throw an `ArgumentOutOfRangeException` if the number of partitions is not a positive integer [`Partitioner.cs:55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Partitioner.cs#L55).
