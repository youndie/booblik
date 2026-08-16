# Connection (/wiki/clients-dotnet-Booblik/connection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Connection` class provides a low-level, non-thread-safe transport for the Booblik protocol, managing the lifecycle of a single TCP connection and the framing of request/response pairs ([`Connection.cs:40-41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L40-L41)).

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (Caller)
    participant Conn as Connection
    participant B as Broker

    C->>Conn: SendAsync(payload)
    Note over Conn: Increment correlationId
    Conn->>B: Write Frame (Length + Header + Payload)
    B-->>Conn: Write Frame (Length + Header + Payload)
    Note over Conn: Validate correlationId & Code
    Conn-->>C: Return Payload / Result"
/>

## Connection Lifecycle and Thread Safety [#connection-lifecycle-and-thread-safety]

The `Connection` class is explicitly marked as not safe for concurrent use ([`Connection.cs:36-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L36-L38)). Because requests and responses are matched by a `correlationId` in the order they are sent ([`Connection.cs:36-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L36-L37)), multiple callers sharing a single `Connection` would cause interleaved data, where one caller reads the response intended for another ([`Connection.cs:37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L37)). To avoid this, the architecture requires one `Connection` per caller or the use of a `Producer` which manages its own instance ([`Connection.cs:38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L38)).

## Request-Response Correlation and Framing [#request-response-correlation-and-framing]

The communication relies on a strict framing mechanism where `SendAsync` increments a `_correlation` counter and writes a frame containing the `apiKey`, `apiVersion`, and `correlationId` ([`Connection.cs:90-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L90-L97)). The `ReceiveAsync` method then reads the response frame and performs a critical check: if the received `correlation` does not match the `expect` value, a `ProtocolException` is thrown ([`Connection.cs:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L124-L126)). This ensures that the client does not misinterpret a response as belonging to a different request, which would otherwise lead to incorrect offsets being handed to the wrong caller ([`Connection.cs:122-123`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L122-L123)).

## Protocol Error Handling and Validation [#protocol-error-handling-and-validation]

The client performs several layers of validation during the response phase:

* **Frame Integrity**: If the response length is outside the allowed range (`ResponseHeaderBytes` to `MaxFrameBytes`), a `ProtocolException` is raised ([`Connection.cs:110-112`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L110-L112)).
* **Correlation Matching**: As noted above, mismatched IDs trigger a `ProtocolException` ([`Connection.cs:126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L126)).
* **Broker Errors**: If the response contains a non-zero error code, a `BrokerException` is thrown ([`Connection.cs:131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L131)).

## Metadata Decoding and Partition Information [#metadata-decoding-and-partition-information]

The `MetadataAsync` method triggers a request to find topics and their partition states ([`Connection.cs:145`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L145)). The response is processed by `DecodeMetadata`, which iterates through the byte stream to extract:

| Field            | Type   | Description                                                                                                                                                                                                                                  |
| ---------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `topicCount`     | Int32  | Number of topics in the response ([`Connection.cs:310`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L310))                                                         |
| `nameLength`     | UInt16 | Length of the topic name ([`Connection.cs:315`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L315))                                                                 |
| `partitionCount` | Int32  | Number of partitions for the current topic ([`Connection.cs:320`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L320))                                               |
| `PartitionInfo`  | Struct | Contains `Partition` (int), `LogStartOffset` (long), and `HighWatermark` (long) ([`Connection.cs:327-329`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L327-L329)) |

## Produce and Fetch Request Mechanics [#produce-and-fetch-request-mechanics]

The client supports different interaction patterns for data movement:

**ProduceAsync Payload Structure:**

| Field             | Type   | Description                                                                                                                                                                                |
| ----------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `topicNameLength` | UInt16 | Length of the topic name ([`Connection.cs:194`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L194))               |
| `partition`       | Int32  | Target partition ID ([`Connection.cs:199`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L199))                    |
| `ack`             | Byte   | `AckPolicy` (None, Written, or Forced) ([`Connection.cs:201`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L201)) |
| `recordCount`     | Int32  | Number of records in the batch ([`Connection.cs:202`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L202))         |

**FetchAsync (v2) Parameters:**

| Parameter       | Type  | Description                                                                                                                                                                                  |
| --------------- | ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `maxBytes`      | Int32 | Bounds the response in bytes ([`Connection.cs:263`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L263))             |
| `maxWaitMillis` | Int32 | How long the broker may hold a request ([`Connection.cs:264`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L264))   |
| `minBytes`      | Int32 | Minimum bytes required before responding ([`Connection.cs:265`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L265)) |

## Key files [#key-files]

| File                                                                                                                                                                                                | Lines     | What is there                                                            |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------ |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L88-L102 "clients/dotnet/Booblik/Connection.cs")  | `88-102`  | `SendAsync` implementation for framing and writing to the stream         |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L104-L135 "clients/dotnet/Booblik/Connection.cs") | `104-135` | `ReceiveAsync` implementation for reading and validating response frames |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L303-L344 "clients/dotnet/Booblik/Connection.cs") | `303-344` | `DecodeMetadata` logic for parsing the metadata byte stream              |

## Behaviour that surprise [#behaviour-that-surprise]

* The `Connection` class is not thread-safe; sharing it between callers will cause them to read each other's answers due to the `correlationId` matching logic ([`Connection.cs:36-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L36-L38)).
* A `ProduceAsync` call with `AckPolicy.None` will return `null` immediately without waiting for a response from the broker ([`Connection.cs:214-216`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L214-L216)).
* `MetadataAsync` will fail the entire request with an error if a single requested topic does not exist, rather than simply omitting it from the results ([`Connection.cs:141-143`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L141-L143)).
