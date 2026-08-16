# BooblikConnection (/wiki/booblik-client/booblikconnection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `BooblikConnection` class is responsible for managing a single TCP connection to a Booblik broker. It implements a pipelined communication protocol where multiple requests can be in flight simultaneously. It handles the encoding of requests, the asynchronous reading of responses, and the complex task of matching those responses back to the specific coroutines that requested them, all while maintaining strict FIFO ordering guarantees.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller (Coroutine)
    participant W as Writer Coroutine
    participant S as SocketChannel
    participant B as Broker

    C->>W: send(Outgoing(frame, Pending))
    W->>S: writeFully(frame)
    S->>B: TCP Stream
    B-->>S: Response Frame
    S->>W: readFrame()
    W->>C: Pending.complete(frame)"
/>

## Pipelined Request-Response Matching [#pipelined-request-response-matching]

Because the broker is guaranteed to answer requests in the exact order they were received, the client uses a FIFO queue to manage pending responses. When a request is sent, a `Pending` object is added to a `ConcurrentLinkedQueue` (as seen in [`BooblikConnection.kt:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60)). When a response arrives, the `reader` coroutine polls the head of this queue and validates that the `correlationId` in the response matches the expected ID via `checkOrder` (as seen in [`BooblikConnection.kt:193`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L193)). If the IDs do not match, it indicates the broker has violated the protocol, and the client fails loudly.

## The Outbound Writer Coroutine [#the-outbound-writer-coroutine]

To prevent interleaved writes where bytes from two different requests might mix on the wire, `BooblikConnection` uses a single-writer pattern. All callers send their requests to an `outbound` `Channel` (as seen in [`BooblikConnection.kt:59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L59)), and a single dedicated `writer` coroutine drains this channel to perform the actual socket writes (as seen in [`BooblikConnection.kt:73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L73)). This ensures that the order in which requests are enqueued in the channel is exactly the order in which they are written to the `SocketChannel`.

## AckPolicy.NONE and Fire-and-Forget Semantics [#ackpolicynone-and-fire-and-forget-semantics]

The client supports a "fire-and-forget" mode through the `AckPolicy.NONE` setting. When a request is produced with this policy, the `produce` function skips the creation of a `CompletableDeferred` and does not add anything to the `pending` queue (as seen in [`BooblikConnection.kt:110`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L110)). The request is simply sent to the `outbound` channel, and the function returns `null` immediately, bypassing the response matching logic entirely.

## ConnectionClosedException and Failure Propagation [#connectionclosedexception-and-failure-propagation]

When a connection fails or is closed, the client must ensure that no caller is left hanging forever. The `fail` function handles this by catching the error and iterating through the `pending` queue to fail every waiting request with a `ConnectionClosedException` (as seen in [`BooblikConnection.kt:166`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L166)). This ensures that all suspended `await()` calls in the calling coroutines are resumed with an exception, maintaining the integrity of the asynchronous flow.

## Correlation ID Atomicity [#correlation-id-atomicity]

To support high concurrency, `BooblikConnection` uses an `AtomicInteger` to generate `correlationIds` (as seen in [`BooblikConnection.kt:71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L71)). This is critical because multiple coroutines call `produce`, `metadata`, or `fetch` concurrently. Using `getAndIncrement()` ensures that every single request receives a unique, monotonically increasing ID, preventing the catastrophic failure where two different callers receive each other's responses (as noted in the documentation at [`BooblikConnection.kt:67`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L67)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                             | Lines    | What is there                                                                                                      |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------ |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-241` | The implementation of the pipelined connection, including the writer/reader coroutines and request matching logic. |

## Behaviour that surprise [#behaviour-that-surprise]

* **Strict Ordering Requirement**: If the broker sends a response out of order, the client will throw an error rather than attempting to find the correct caller, because the protocol's FIFO guarantee is a prerequisite for correctness (as seen in [`BooblikConnection.kt:34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L34)).
* **Blocking the Pipeline**: A single `fetch` request with a long `maxWaitMillis` will block all subsequent requests on the same connection, including `produce` requests, because the connection is served strictly one request at a time (as seen in [`BooblikConnection.kt:132`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L132)).
* **Silent Failures in Acceptor**: In the server implementation, if the acceptor loop encounters an exception, it is treated as transient to prevent the broker from entering a state where it accepts TCP connections but never processes them (as seen in [`BooblikServer.kt:169`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L169)).
