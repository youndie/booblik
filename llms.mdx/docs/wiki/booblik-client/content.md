# booblik-client (/wiki/booblik-client)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Producer
    participant C as BooblikConnection
    participant B as Broker
    participant D as Consumer

    P->>C: send(record)
    Note over P,C: Accumulates in Batch
    C->>B: produce(batch)
    B-->>C: ProduceResult
    C-->>P: CompletableDeferred<Offset>

    D->>C: poll()
    C->>B: fetch(offset)
    B-->>C: FetchResult
    C-->>D: Records"
/>

## BooblikClient [#booblikclient]

Low-level blocking client for direct socket communication. It provides the foundation for all network activity by managing a single `SocketChannel` and performing raw reads and writes of request frames [`BooblikClient.kt:27-88`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L27-L88).

More: [BooblikClient](booblik-client/booblikclient)

## BooblikConnection [#booblikconnection]

Pipelined connection with asynchronous request-response matching. This class manages a single socket where multiple requests can be in flight simultaneously; it uses a `ConcurrentLinkedQueue` of `Pending` objects to match incoming responses to their original callers via correlation IDs [`BooblikConnection.kt:60-241`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60-L241).

## Producer [#producer]

Record accumulation and batching mechanism. The `Producer` uses an internal `mailbox` to collect records and groups them into `Batch` objects to maximize throughput, as batching is identified as the most significant performance factor [`Producer.kt:37-173`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L37-L173).

More: [Producer](booblik-client/producer)

## Consumer [#consumer]

Partition reading and position management. The `Consumer` tracks the `position` (offset) locally rather than relying on the broker to maintain state [`Consumer.kt:56-65`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L65), allowing for efficient, zero-copy-friendly reading of partition data [`Consumer.kt:56-97`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L97).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                             | Lines    | What is there                                   |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------- |
| [`…/client/BooblikClient.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L88 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt")              | `24-88`  | The low-level blocking socket client.           |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-241` | The pipelined, asynchronous connection handler. |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L263 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                            | `54-263` | The batching producer implementation.           |
| [`…/client/Consumer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L113 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt")                            | `56-113` | The partition-based consumer implementation.    |

## Public API [#public-api]

| What                             | Where                                                                                                                                                                                                      | Why                                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `BooblikClient`                  | [`BooblikClient.kt:24`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24)         | Provides the base socket connection.                      |
| `ConnectionClosedException`      | [`BooblikConnection.kt:25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L25) | Thrown when a connection dies while requests are pending. |
| `BooblikConnection`              | [`BooblikConnection.kt:50`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50) | Manages pipelined, asynchronous communication.            |
| `ProduceFailedException`         | [`Producer.kt:17`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L17)                   | Thrown when the broker refuses a record.                  |
| `ProducerConfig`                 | [`Producer.kt:21`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L21)                   | Configuration for batching and acknowledgment.            |
| `Producer`                       | [`Producer.kt:54`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54)                   | High-level API for sending batched records.               |
| `FetchFailedException`           | [`Consumer.kt:8`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L8)                     | Thrown when a fetch request fails.                        |
| `RecordExceedsMaxBytesException` | [`Consumer.kt:28`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L28)                   | Thrown when a record is too large to fit in `maxBytes`.   |
| `Records`                        | [`Consumer.kt:38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L38)                   | Data class containing a batch of fetched records.         |
| `Consumer`                       | [`Consumer.kt:56`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56)                   | High-level API for reading partition data.                |

## Behaviour that surprises [#behaviour-that-surprises]

* In `Producer.runLoop`, the use of `select` with `onTimeout` is critical; using `withTimeoutOrNull` would cancel the `mailbox.receive()` operation, potentially causing the client to drop records and leaving callers waiting forever [`Producer.kt:143-158`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L143-L158).
* The `Consumer.poll` method can throw a `RecordExceedsMaxBytesException` if a record is larger than the configured `maxBytes`, which results in a permanent stall if the caller does not increase the limit [`Consumer.kt:28-35`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L28-L35).
* `BooblikConnection` enforces strict FIFO ordering; if the broker responds out of order, the `checkOrder` function will trigger a failure to prevent delivering the wrong response to a caller [`BooblikConnection.kt:192-194`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L192-L194).
