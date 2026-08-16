# BooblikClient (/wiki/booblik-client/booblikclient)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as BooblikConnection
    participant O as Outbound Channel
    participant P as Pending Queue
    participant S as SocketChannel

    C->>O: send(Request + Pending)
    O->>P: add(Pending)
    O->>S: writeFully(Request)
    S-->>C: readFrame(Response)
    C->>P: poll()
    P->>C: complete(Response)"
/>

## BooblikClient [#booblikclient]

The low-level, blocking, single-socket client interface.
`BooblikClient` provides a raw, blocking interface to the broker, where sending and receiving are separate calls to allow for manual pipelining [`BooblikClient.kt:18-22`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L18-L22). It manages a single `SocketChannel` and uses a `nextCorrelationId` to tag requests [`BooblikClient.kt:37`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L37).

## BooblikConnection [#booblikconnection]

Pipelined request management and correlation ID matching.
`BooblikConnection` manages a pipelined connection where multiple requests can be in flight simultaneously [`BooblikConnection.kt:31`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L31). It uses an `AtomicInteger` to ensure that every caller receives a unique correlation ID, preventing the same ID from being handed to two different callers [`BooblikConnection.kt:71`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L71).

## The Pending Queue and FIFO Ordering [#the-pending-queue-and-fifo-ordering]

How the client ensures broker response order matches request order via the Pending sealed class.
To maintain the protocol's ordering guarantee, the client uses a `ConcurrentLinkedQueue` of `Pending` objects [`BooblikConnection.kt:60`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60). The `Pending` sealed class hierarchy (including `Produce`, `Metadata`, and `Fetch`) implements `checkOrder` to verify that the broker's response correlation ID matches the expected ID, failing loudly if the broker reorders responses [`BooblikConnection.kt:192-195`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L192-L195).

## Fetch Wait Semantics [#fetch-wait-semantics]

Behavior of maxWaitMillis and the impact of held requests on connection pipelining.
When a `fetch` request is sent with a `maxWaitMillis` greater than zero, the broker may hold the request until more data is available or the timeout expires [`LongFetchTest.kt:58`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/LongFetchTest.kt#L58). Because the broker serves requests in order, a held fetch blocks all subsequent requests on that same connection, including `PRODUCE` requests [`BooblikConnection.kt:132-136`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L132-L136).

## ConnectionClosedException and Failure Propagation [#connectionclosedexception-and-failure-propagation]

How the client handles socket closures and propagates errors to waiting callers.
If a connection fails, the `fail` function iterates through all remaining `Pending` objects in the queue and completes them with a `ConnectionClosedException` [`BooblikConnection.kt:166-167`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L166-L167). This ensures that callers waiting on `await()` are not left hanging indefinitely when the socket closes [`BooblikConnection.kt:166`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L166).

## Zero-Copy Transfer via transferTo [#zero-copy-transfer-via-transferto]

The requirement for SocketChannel to enable sendfile and the risks of wrapping the channel.
The `transferFrom` method relies on `FileChannel.transferTo` to achieve zero-copy via `sendfile` [`Connection.kt:48`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L48). However, the `transferTarget` must be a real `SocketChannel`; wrapping the channel in a decorator can silently turn the read path into a standard heap-buffer copy loop [`SendfileTest.kt:73-76`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L73-L76).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                            | Lines   | What is there                                                            |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------------------ |
| [`…/client/BooblikClient.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L24-L49 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt")             | `24-49` | The `BooblikClient` class providing blocking send/receive methods.       |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L97 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-97` | The `BooblikConnection` class managing the reader and writer coroutines. |
| [`…/nio/Connection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L23-L53 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt")                                     | `23-53` | The `Connection` interface defining `transferFrom` and `transferTarget`. |

## Behaviour that surprise [#behaviour-that-surprise]

* A held `fetch` request on a `BooblikConnection` blocks all subsequent requests on that same connection, meaning a consumer and a producer cannot share a connection if the consumer is waiting for data [`BooblikConnection.kt:132-136`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L132-L136).
* Wrapping a `SocketChannel` in a decorator (like a metrics or TLS layer) can silently disable `sendfile` optimizations, causing `transferTo` to fall back to a heap-buffer copy loop without changing the resulting data [`SendfileTest.kt:39-41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L39-L41).
