# booblik-native (/wiki/booblik-native)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 5. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Producer
    participant C as BooblikConnection
    participant S as Socket
    participant B as Broker

    P->>C: produce(records)
    C->>S: writeFully(frame)
    S->>B: TCP Stream
    B-->>S: TCP Stream
    S->>C: readFrame()
    C-->>P: CompletableDeferred<Offset?>"
/>

## BooblikConnection [#booblikconnection]

The core connection to a broker over a blocking POSIX socket, managing correlation IDs and frame decoding (`booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt:30-35, 158-172`). It ensures that responses are matched to the correct requests via correlation IDs ([`Connection.kt:34-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L34-L35)) and handles the decoding of protocol frames ([`Connection.kt:158-172`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L158-L172)).

More: [BooblikConnection](booblik-native/booblikconnection)

## Socket [#socket]

Low-level POSIX socket implementation providing blocking read and write operations (`booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt:105-143, 146-147`). It uses `getaddrinfo` to resolve hostnames and handles the raw byte transfer via `send` and `recv` ([`Socket.kt:60-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L60-L70)).

More: [Socket](booblik-native/socket)

## Producer [#producer]

An accumulator that batches records by partition and uses a single-threaded dispatcher to manage asynchronous sending ([`Producer.kt:80-177`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L80-L177)). It uses a `mailbox` to receive commands and a `runLoop` to manage the timing of batch flushes ([`Producer.kt:81-177`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L81-L177)).

More: [Producer](booblik-native/producer)

## Consumer [#consumer]

A blocking reader for a specific topic and partition, managing position and high watermark tracking ([`Consumer.kt:56-143`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt#L56-L143)). It provides a `Sequence` of records by repeatedly calling `poll` ([`Consumer.kt:135-140`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt#L135-L140)).

## Topic [#topic]

A high-level handle for a topic that provides partition selection via round-robin or key-based partitioning ([`Connection.kt:176-197`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L176-L197)). It allows users to find the correct partition for a given key using a partitioner ([`Connection.kt:190-197`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L190-L197)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                            | Lines    | What is there                                                           |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------------------------- |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L42-L83 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt")              | `42-83`  | The `Socket` companion object containing the `connect` logic.           |
| [`…/native/Consumer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt#L56-L143 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt")       | `56-143` | The `Consumer` class implementation.                                    |
| [`…/native/Producer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L75-L275 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt")       | `75-275` | The `Producer` class and its internal `Command` and `Batch` structures. |
| [`…/native/Connection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L30-L207 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt") | `30-207` | The `BooblikConnection` and `Topic` classes.                            |

## Public API [#public-api]

| What                | Where                                                                                                                                                                                            | Why                                                         |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------- |
| `Consumer`          | [`Consumer.kt:56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt#L56)       | To read records from a specific topic and partition.        |
| `Producer`          | [`Producer.kt:75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L75)       | To accumulate and send records in batches.                  |
| `BooblikConnection` | [`Connection.kt:30`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L30)   | To manage the underlying socket and protocol communication. |
| `Topic`             | [`Connection.kt:176`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L176) | To interact with a specific topic and its partitions.       |

## Behaviour that surprises [#behaviour-that-surprises]

* The `Producer` uses a `newSingleThreadContext` because `Dispatchers.IO` is `internal` on Kotlin/Native, meaning the producer must own its own thread to avoid blocking core-limited dispatchers ([`Producer.kt:80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Producer.kt#L80)).
* The `Consumer.poll` function advances the `position` only after whole records are fetched; if a response is truncated due to `maxBytes`, the partial tail is dropped and the next poll starts from the beginning of that record ([`Consumer.kt:105-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Consumer.kt#L105-L108)).
* `BooblikConnection.produce` returns `null` when `AckPolicy.NONE` is used, as no offset is available to report ([`Connection.kt:59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L59)).
