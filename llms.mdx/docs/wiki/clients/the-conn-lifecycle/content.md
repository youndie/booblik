# The `Conn` Lifecycle and Concurrency (/wiki/clients/the-conn-lifecycle)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller (Coroutine)
    participant O as Outbound Channel
    participant W as Writer Coroutine
    participant S as Socket (SocketChannel)
    participant R as Reader Coroutine
    participant P as Pending Queue

    C->>O: send(Outgoing)
    O->>W: message
    W->>P: add(Pending)
    W->>S: writeFully(frame)
    S-->>R: data received
    R->>P: poll()
    R->>P: checkOrder(correlationId)
    P-->>C: complete(result)"
/>

## The `Conn` Thread-Safety Model [#the-conn-thread-safety-model]

The low-level `Conn` implementation in Go is explicitly not safe for concurrent use; it requires one connection per goroutine because requests and responses are matched by a correlation ID in the order they were sent, and sharing a `Conn` would cause goroutines to read each other's answers ([`booblik.go:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L75-L77)). In the Kotlin implementation, while the `BooblikConnection` is designed for concurrent access, it maintains this strict ordering by using a single writer coroutine to prevent interleaved writes ([`BooblikConnection.kt:40-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L40-L46)).

## The `BooblikConnection` Pipelining Mechanism [#the-booblikconnection-pipelining-mechanism]

To allow multiple callers to use a single connection without interleaving bytes, `BooblikConnection` uses a `Channel` to queue `Outgoing` messages ([`BooblikConnection.kt:59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L59)). A single writer coroutine drains this channel, ensuring that the order in which requests are enqueued is the exact order in which they are written to the `SocketChannel` ([`BooblikConnection.kt:74-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L74-L83)).

## Correlation ID Matching and `Pending` Requests [#correlation-id-matching-and-pending-requests]

Asynchronous responses are matched back to their original callers using a `ConcurrentLinkedQueue` of `Pending` objects ([`BooblikConnection.kt:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60)). The `Pending` sealed class hierarchy manages different response types:

| Class      | Purpose                                           |
| ---------- | ------------------------------------------------- |
| `Produce`  | Completes a `CompletableDeferred<ProduceResult>`  |
| `Metadata` | Completes a `CompletableDeferred<MetadataResult>` |
| `Fetch`    | Completes a `CompletableDeferred<FetchResult>`    |

The `reader` coroutine polls the head of this queue and uses `checkOrder` to verify that the `correlationId` in the response matches the expected ID ([`BooblikConnection.kt:192-195`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L192-L195)).

## Failure Propagation and `ConnectionClosedException` [#failure-propagation-and-connectionclosedexception]

When a connection failure occurs, the `fail` method is called to ensure no caller is left waiting forever ([`BooblikConnection.kt:162-168`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L162-L168)). This method iterates through all remaining `Pending` requests in the queue and calls `fail(ConnectionClosedException(cause))` on each one ([`BooblikConnection.kt:166-167`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L166-L167)).

## Error Handling and Protocol Integrity [#error-handling-and-protocol-integrity]

The client enforces protocol integrity by validating that the broker's response matches the request's `correlationId` ([`BooblikConnection.kt:194`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L194)). If a response carries a different ID, it is treated as a critical error because it implies the broker is reordering responses, which would lead to delivering the wrong data to the wrong caller ([`ErrorCodeTest.kt:19-21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ErrorCodeTest.kt#L19-L21)). Additionally, the client must handle `ErrorCode` responses, such as `UNSUPPORTED_VERSION`, which are returned as part of the response frame ([`ErrorCodeTest.kt:39-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ErrorCodeTest.kt#L39-L40)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                              | Lines     | What is there                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------------------- |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L78-L81 "clients/go/booblik.go")                                                                                                                                                       | `78-81`   | Definition of the non-thread-safe `Conn` struct                   |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L61 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt")   | `50-61`   | The `BooblikConnection` class and its internal queueing mechanism |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L184-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `184-241` | The `Pending` sealed class hierarchy for request matching         |

## Behaviour that surprises [#behaviour-that-surprises]

* **Strict Ordering Requirement**: A `BooblikConnection` is a pipelined connection where the broker is expected to answer in strict FIFO order; if the broker reorders responses, the client will throw an error rather than silently delivering the wrong data ([`BooblikConnection.kt:34-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L34-L38)).
* **Health Check Semantics**: A health check using `Metadata` is preferred over a simple TCP connect because a TCP handshake can succeed even if the broker process is hung and unable to process requests ([`Health.kt:14-18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L14-L18)).
* **Correlation ID Persistence**: Even when the broker returns an error like `UNSUPPORTED_VERSION`, it is required to echo the `correlationId` so the client can identify which request failed ([`ErrorCodeTest.kt:39-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ErrorCodeTest.kt#L39-L40)).
