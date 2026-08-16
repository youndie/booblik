# booblik-client (/wiki/booblik-client)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant App as Application Code
    participant P as Producer
    participant C as Consumer
    participant BC as BooblikConnection
    participant B as Broker

    App->>P: send(record)
    P->>P: accumulate in Batch
    Note over P: lingerMillis or maxBatchSize
    P->>BC: produce(batch)
    BC->>BC: write to outbound channel
    BC->>B: write bytes (single writer)
    B-->>BC: response (FIFO)
    BC->>P: complete(offset)
    
    App->>C: poll()
    C->>BC: fetch(position)
    BC->>B: fetch request
    B-->>BC: fetch response (zero-copy)
    BC->>C: return Records"
/>

## BooblikClient [#booblikclient]

The low-level, blocking, single-socket client for sending raw requests. It provides the most basic interface for sending `PRODUCE`, `FETCH`, and `METADATA` requests directly to a `SocketChannel` [`BooblikClient.kt:24-88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L88).

More: [BooblikClient](booblik-client/booblikclient)

## BooblikConnection [#booblikconnection]

The pipelined connection mechanism that manages concurrent requests via a single writer coroutine and a FIFO response queue. It uses an `AtomicInteger` to manage `correlationId`s [`BooblikConnection.kt:71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L71) and ensures that responses are matched to callers in strict order, failing loudly if the broker reorders them [`BooblikConnection.kt:34-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L34-L38).

More: [BooblikConnection](booblik-client/booblikconnection)

## Producer [#producer]

The high-level accumulator that batches records by partition and manages the `lingerMillis` and `maxBatchSize` logic [`Producer.kt:21-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L21-L34). It uses a `mailbox` to receive `Command.Append` requests and an internal `runLoop` to decide when to trigger a `deliver` call based on the configured `ProducerConfig` [`Producer.kt:54-173`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L173).

More: [Producer](booblik-client/producer)

## Consumer [#consumer]

The stateful reader that manages the `position` offset and handles `RecordExceedsMaxBytesException` during polling [`Consumer.kt:56-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L97). It tracks the current `position` and advances it only past whole records when a `poll` is successful [`Consumer.kt:56-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L97).

## ResponseReader [#responsereader]

The decoding layer that handles frame reading and checksum verification for FETCH responses. It reads the length-prefixed frames from a `SocketChannel` and delegates decoding to the protocol module, specifically handling the translation of `CorruptRecordException` [`ResponseReader.kt:55-109`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L55-L109).

## ResponseEncoder [#responseencoder]

The construction of response frames, specifically the zero-copy header mechanism for FETCH responses. It distinguishes between "promised" bytes (the body to be streamed) and "inline" bytes (the header) to support efficient zero-copy reading [`ResponseEncoder.kt:15-117`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/ResponseEncoder.kt#L15-L117).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                             | Lines    | What is there                                               |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------------- |
| [`…/client/BooblikClient.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L88 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt")              | `24-88`  | The low-level blocking client implementation.               |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-241` | The pipelined connection and coroutine-based writer/reader. |
| [`…/client/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54-L264 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt")                            | `54-264` | The batching producer implementation.                       |
| [`…/client/Consumer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56-L113 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt")                            | `56-113` | The stateful consumer implementation.                       |
| [`…/client/ResponseReader.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L55-L118 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt")          | `55-118` | The response decoding and frame reading logic.              |
| [`…/wire/ResponseEncoder.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/ResponseEncoder.kt#L15-L130 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/ResponseEncoder.kt")             | `15-130` | The logic for encoding response frames and headers.         |

## Public API [#public-api]

| What                | Where                                                                                                                                                                                                      | Why                                           |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `BooblikClient`     | [`BooblikClient.kt:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24)         | Low-level client for raw requests.            |
| `BooblikConnection` | [`BooblikConnection.kt:50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50) | Pipelined connection for concurrent requests. |
| `Producer`          | [`Producer.kt:54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L54)                   | High-level batching producer.                 |
| `ProducerConfig`    | [`Producer.kt:21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L21)                   | Configuration for batching and ack policy.    |
| `Consumer`          | [`Consumer.kt:56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L56)                   | Stateful partition reader.                    |
| `ResponseReader`    | [`ResponseReader.kt:55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L55)       | Object for decoding response frames.          |
| `ResponseEncoder`   | [`ResponseEncoder.kt:15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/ResponseEncoder.kt#L15)       | Object for encoding response frames.          |

## Behaviour that surprises [#behaviour-that-surprises]

* The `Producer` uses a `select` expression in its `runLoop` to avoid a specific bug where a cancelled `receive` could drop elements from the `mailbox`, potentially causing callers to wait forever [`Producer.kt:154-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Producer.kt#L154-L158).
* The `Consumer` can throw a `RecordExceedsMaxBytesException` if a record is larger than the `maxBytes` limit, which results in a permanent stall if the reader does not increase its limit [`Consumer.kt:28-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L28-L36).
* The `ResponseEncoder` uses a "promised" vs "inline" byte mechanism for `fetchHeader` to allow the client to perform zero-copy reads of the response body [`ResponseEncoder.kt:39-41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/ResponseEncoder.kt#L39-L41).
