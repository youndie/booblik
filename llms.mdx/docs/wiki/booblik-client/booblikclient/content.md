# BooblikClient (/wiki/booblik-client/booblikclient)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller (Coroutine)
    participant BC as BooblikConnection
    participant S as SocketChannel
    participant B as BooblikServer

    C->>BC: produce(records)
    Note over BC: Increment correlationId
    BC->>BC: Enqueue Outgoing to Channel
    BC->>S: writeFully(frame)
    S->>B: TCP Stream
    B-->>S: Response Frame
    S->>BC: readFrame()
    BC->>BC: poll() pending queue
    BC->>C: complete(ProduceResult)"
/>

## BooblikClient [#booblikclient]

The low-level, blocking, single-socket client interface. `BooblikClient` acts as a thin wrapper around a `SocketChannel`, providing synchronous methods to send metadata, produce records, and fetch data [`BooblikClient.kt:24-88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L88). It is designed to be "dumb"—it performs no bookkeeping or request queuing itself, leaving that responsibility to higher-level abstractions.

## Pipelined Requests and Correlation IDs [#pipelined-requests-and-correlation-ids]

Because the broker is guaranteed to answer requests in the exact order they were received, the client can support pipelining. Each request is assigned a unique `correlationId` via an `AtomicInteger` [`BooblikConnection.kt:71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L71). When a response arrives, the client uses a `ConcurrentLinkedQueue` of `Pending` objects to match the response back to the original caller [`BooblikConnection.kt:60-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60-L70). If the broker responds out of order, the client detects the mismatch and fails loudly to prevent delivering data to the wrong caller [`BooblikConnection.kt:192-195`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L192-L195).

## BooblikConnection [#booblikconnection]

The coroutine-based implementation of a pipelined connection. It manages two long-running jobs: a `writer` that drains an `outbound` channel to the socket and a `reader` that continuously parses incoming frames [`BooblikConnection.kt:74-100`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L74-L100). This architecture ensures that multiple coroutines can concurrently call `produce` or `fetch` without interleaving bytes on the wire, as all writes are serialized through a single coroutine [`BooblikConnection.kt:40-47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L40-L47).

## AckPolicy and Response Behavior [#ackpolicy-and-response-behavior]

The `AckPolicy` determines whether a producer waits for a response from the broker [`BooblikClient.kt:44`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L44).

| Policy    | Behavior                                                                                                                                                                                                                                                                                    |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `NONE`    | The request is sent, but no response is expected; the client returns `null` immediately [`BooblikClient.kt:48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L48). |
| `WRITTEN` | The client waits for a `ProduceResult` from the broker [`BooblikClient.kt:48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L48).                                  |
| `FORCED`  | The client waits for the broker to acknowledge the write (implementation details handled by the broker).                                                                                                                                                                                    |

## Fetch Long-Polling and maxWaitMillis [#fetch-long-polling-and-maxwaitmillis]

The `fetch` request supports long-polling via the `maxWaitMillis` parameter [`BooblikClient.kt:57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L57). If the broker has no data, it can "hold" the request for up to the specified duration. While a request is held, it occupies the connection, meaning subsequent requests on that same connection are blocked [`LongFetchTest.kt:117-118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/LongFetchTest.kt#L117-L118). A record arriving at the broker during this wait period will wake the fetch immediately [`LongFetchTest.kt:52-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/LongFetchTest.kt#L52-L70).

## Conformance Testing Scenarios [#conformance-testing-scenarios]

The conformance client verifies the protocol implementation against specific requirements [`Main.kt:37-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L37-L80). Key checks include:

* **Keyed Production**: Using `Partitioner.Fnv1a` to select a partition based on a key before sending the produce request [`Main.kt:158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L158).
* **Error Reporting**: Ensuring that `CORRUPT_REQUEST` errors (such as `minBytes` being larger than `maxBytes`) are correctly propagated to the client [`Main.kt:182`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                             | Lines    | What is there                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------- |
| [`…/client/BooblikClient.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L88 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt")              | `24-88`  | The low-level blocking client implementation.         |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-241` | The pipelined, coroutine-based connection logic.      |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L37-L80 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt")                        | `37-80`  | The reference implementation for conformance testing. |

## Behaviour that surprise [#behaviour-that-surprise]

* A held fetch request on a `BooblikConnection` blocks all subsequent requests on that same connection, but does not block requests sent from a different connection [`LongFetchTest.kt:97-118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/LongFetchTest.kt#L97-L118).
* If a `fetch` request is cancelled via a timeout, the abandoned read remains on the socket and may consume the response intended for the next read [`LongFetchTest.kt:37-41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/LongFetchTest.kt#L37-L41).
