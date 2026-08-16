# booblik-protocol (/wiki/booblik-protocol)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblik-protocol` module defines the shared language between a client and a broker. It contains the wire format constants, the client-side request encoding logic, and the core domain types (like `Offset` and `TopicName`) that ensure both sides interpret the byte stream identically.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant Client
    participant Broker
    Note over Client, Broker: Wire Protocol (Big-Endian)
    Client->>Broker: Request (PRODUCE/FETCH/METADATA)
    Note right of Client: Encoded by RequestEncoder
    Broker-->>Client: Response (Success/ErrorCode)"
/>

## Protocol Constants and Versioning [#protocol-constants-and-versioning]

The protocol uses a big-endian byte order to allow `FETCH` responses to hand segment bytes directly to a socket without modification (`Protocol.kt:8-10`). The following table describes the header structures and limits:

| Component       | Structure / Limit                                                     | Description                                               |
| --------------- | --------------------------------------------------------------------- | --------------------------------------------------------- |
| Request Header  | `[int32 length][int16 apiKey][int16 apiVersion][int32 correlationId]` | Standard header for all requests (`Protocol.kt:19`)       |
| Response Header | `[int32 correlationId][int16 errorCode]`                              | Standard header for all responses (`Protocol.kt:22`)      |
| Max Frame Size  | `8 * 1024 * 1024` bytes                                               | Ceiling to prevent OOM attacks (`Protocol.kt:31`)         |
| Max Fetch Wait  | `60,000` ms                                                           | Clamped wait time for `FETCH` requests (`Protocol.kt:44`) |

The `supports` function determines version compatibility for specific APIs (`Protocol.kt:54-57`).

More: [Protocol Constants and Versioning](booblik-protocol/protocol-constants-and)

## RequestEncoder [#requestencoder]

The `RequestEncoder` is the client-side implementation responsible for building request frames (`RequestEncoder.kt:18`). It handles the following API types:

| API Key    | Version         | Description                                                                                 |
| ---------- | --------------- | ------------------------------------------------------------------------------------------- |
| `PRODUCE`  | `VERSION`       | Sends records to a specific topic and partition (`RequestEncoder.kt:20`)                    |
| `FETCH`    | `FETCH_VERSION` | Requests data from a partition with `maxWaitMillis` and `minBytes` (`RequestEncoder.kt:55`) |
| `METADATA` | `VERSION`       | Requests topic and partition information (`RequestEncoder.kt:85`)                           |

## AckPolicy [#ackpolicy]

The `AckPolicy` defines the durability promises made to the producer (`AckPolicy.kt:21`).

| Policy    | Promise      | Description                                                                         |
| --------- | ------------ | ----------------------------------------------------------------------------------- |
| `NONE`    | Nothing      | Fire-and-forget; no reply is sent (`AckPolicy.kt:22`)                               |
| `WRITTEN` | Bytes in log | Offset is final, but not necessarily durable against power loss (`AckPolicy.kt:23`) |
| `FORCED`  | Durability   | Requires a disk barrier/flush (`AckPolicy.kt:24`)                                   |

More: [AckPolicy](booblik-protocol/ackpolicy)

## Partitioner [#partitioner]

The `Partitioner` interface allows clients to decide which partition a record is assigned to (`Partitioner.kt:11`).

| Algorithm       | Implementation          | Characteristics                                                                               |
| --------------- | ----------------------- | --------------------------------------------------------------------------------------------- |
| `Fnv1a`         | `fnv1a32`               | Uses 32-bit FNV-1a with unsigned remainder for cross-language agreement (`Partitioner.kt:38`) |
| `JavaArrayHash` | `key.contentHashCode()` | A JVM-specific implementation used for legacy compatibility (`Partitioner.kt:73`)             |

## Offset, TopicName, and PartitionId [#offset-topicname-and-partitionid]

These value classes represent the core identifiers used in the protocol (`Ids.kt:1-75`):

| Type          | Underlying Type | Constraints / Notes                                         |
| ------------- | --------------- | ----------------------------------------------------------- |
| `Offset`      | `Long`          | Monotonic, non-negative, gap-free (`Ids.kt:24`)             |
| `TopicName`   | `String`        | Max 249 chars; alphanumeric, `.`, `_`, or `-` (`Ids.kt:46`) |
| `PartitionId` | `Int`           | Non-negative integer (`Ids.kt:66`)                          |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                              | Lines    | What is there                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------------- |
| [`…/log/AckPolicy.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/log/AckPolicy.kt#L21-L25 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/log/AckPolicy.kt")                            | `21-25`  | The `AckPolicy` enum defining durability levels.            |
| [`…/wire/Protocol.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L15-L58 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt")                    | `15-58`  | Protocol constants, API keys, and versioning logic.         |
| [`…/wire/RequestEncoder.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/RequestEncoder.kt#L18-L104 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/RequestEncoder.kt") | `18-104` | Logic for encoding client requests.                         |
| [`…/client/Partitioner.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/client/Partitioner.kt#L11-L81 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/client/Partitioner.kt")     | `11-81`  | Partitioning algorithms (FNV-1a and Java-based).            |
| [`…/booblik/Ids.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/Ids.kt#L23-L75 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/Ids.kt")                                                  | `23-75`  | Value classes for `Offset`, `TopicName`, and `PartitionId`. |

## Public API [#public-api]

| What                      | Where                                                                                                                                                                                                      | Why                                            |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `Protocol`                | [`Protocol.kt:15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L15)             | Provides wire format constants and versioning. |
| `CorruptRequestException` | [`Protocol.kt:105`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L105)           | Thrown when decoding a frame fails.            |
| `RequestEncoder`          | [`RequestEncoder.kt:18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/RequestEncoder.kt#L18) | Encodes client requests into `ByteArray`.      |
| `Partitioner`             | [`Partitioner.kt:11`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/client/Partitioner.kt#L11)     | Interface for mapping keys to partitions.      |
| `Offset`                  | [`Ids.kt:23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/Ids.kt#L23)                                | Represents a logical record position.          |
| `TopicName`               | [`Ids.kt:46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/Ids.kt#L46)                                | Represents a topic identifier.                 |
| `PartitionId`             | [`Ids.kt:66`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/Ids.kt#L66)                                | Represents a partition identifier.             |

## Behaviour that surprises [#behaviour-that-surprises]

* `RequestEncoder` always emits version 2 for `FETCH` requests, even when no waiting is required, to avoid maintaining two separate code paths for the same logic (`RequestEncoder.kt:49-53`).
* `Partitioner.Fnv1a` uses `and 0xFF` during hashing to ensure that Kotlin's signed `Byte` type does not cause sign-extension issues that would break cross-language compatibility (`Partitioner.kt:54`).
* `Offset` is implemented as a `value class` to avoid boxing in most cases, but it will still allocate memory when used in a generic position like `Map<Offset, ...>` (`Ids.kt:15-17`).
