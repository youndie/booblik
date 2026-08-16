# Connection and Topic Management (/wiki/clients-java/connection-and-topic)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (BooblikConnection)
    participant O as Outbound Channel
    participant W as Writer Coroutine
    participant S as Session (Broker)

    C->>O: send(Request)
    Note over C,O: CorrelationId assigned
    O->>W: Enqueue(Request, Pending)
    W->>S: writeFully(Frame)
    S->>S: handle(Frame)
    S->>W: writeFully(Response)
    W->>C: readFrame() -> Response
    Note over C: Match via correlationId"
/>

## BooblikConnection and the Pipelined Request Queue [#booblikconnection-and-the-pipelined-request-queue]

The `BooblikConnection` implements a pipelined architecture where many requests can be in flight simultaneously. To achieve this, it uses a `ConcurrentLinkedQueue` of `Pending` objects to track requests that have been sent but not yet answered [`BooblikConnection.kt:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L60). Because the broker is guaranteed to answer requests in the order they were received, the client uses a FIFO queue to match incoming responses back to their original callers via a `correlationId` [`BooblikConnection.kt:34-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L34-L37). To prevent interleaved writes on the socket, a single writer coroutine drains the outbound channel, ensuring that the wire order matches the request order [`BooblikConnection.kt:74-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L74-L83).

## Session and the Protocol Loop [#session-and-the-protocol-loop]

The `Session` class runs the primary protocol loop, which continuously reads frames from a connection and dispatches them for handling [`Session.kt:50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L50). The lifecycle begins with `readFrame`, which validates the length prefix to prevent large allocation attacks [`Session.kt:68-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L68-L70). Once a frame is read, `handle` uses a `when` expression to decode the request; if `RequestDecoder.decode` returns a `DecodeResult.Failed`, the session immediately responds with an error to the client [`Session.kt:81-89`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L81-L89).

## MetadataRequest and Topic Discovery [#metadatarequest-and-topic-discovery]

`MetadataRequest` is used to discover the state of topics and partitions. When a request is processed, the broker checks if the requested topics exist in its registry [`Session.kt:122`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L122). If a named topic is not found, the broker does not simply omit it from the response; instead, it fails the entire request with an `UNKNOWN_TOPIC_OR_PARTITION` error to prevent clients from waiting indefinitely for a topic that does not exist [`Session.kt:127-130`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L127-L130).

## ConnectionClosedException and Failure Propagation [#connectionclosedexception-and-failure-propagation]

When a network error or a fatal protocol error occurs, the `fail(cause: Throwable)` function is invoked to clean up the connection [`BooblikConnection.kt:161-163`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L161-L163). This function performs several critical tasks:

* It marks the connection as failed.
* It closes the outbound channel.
* It iterates through all `pending` requests in the queue and completes them exceptionally with a `ConnectionClosedException` [`BooblikConnection.kt:166-167`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L166-L167).

## The Producer-Connection Ownership Model [#the-producer-connection-ownership-model]

To maintain the integrity of the pipelined protocol, a `Producer` must own its `Connection`. This is because a `Producer` manages its own pending records and is the only writer to its specific socket [`README.md:49-53`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L49-L53). If a user attempts to use the same `Connection` directly while a `Producer` is active, the responses may be mismatched because the broker's responses are matched strictly by order [`README.md:54-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L54-L56).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                              | Lines     | What is there                                               |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------------- |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L58-L61 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt")   | `58-61`   | Outbound channel and pending request queue                  |
| [`…/net/Session.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L47-L55 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt")                                                        | `47-55`   | The main protocol loop and frame reading                    |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L184-L240 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `184-240` | The `Pending` sealed class hierarchy for matching responses |

## Behaviour that surprising [#behaviour-that-surprising]

* A `MetadataRequest` that names a non-existent topic will result in an error for the entire request rather than just omitting the missing topic [`Session.kt:127-130`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L127-L130).
* If a `Connection` fails, all currently waiting `Pending` requests are immediately failed with a `ConnectionClosedException` [`BooblikConnection.kt:167`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L167).
* The `Session` will continue to keep a connection open even if a `DecodeResult.Failed` occurs, as long as the framing remains intact [`Session.kt:78-79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L78-L79).
