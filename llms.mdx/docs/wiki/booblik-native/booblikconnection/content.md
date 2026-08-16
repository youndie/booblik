# BooblikConnection (/wiki/booblik-native/booblikconnection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Caller
    participant BC as BooblikConnection
    participant S as Socket
    participant B as Broker

    C->>BC: produce(records)
    BC->>BC: increment correlationId
    BC->>S: writeFully(frame)
    S->>B: TCP Stream
    B-->>S: Response Frame
    S->>BC: readFrame()
    BC->>BC: check(response.id == expectedId)
    BC-->>C: ProduceResult"
/>

## BooblikConnection [#booblikconnection]

The `BooblikConnection` class provides a blocking, synchronous connection to a single broker using POSIX sockets via `Socket.kt:33`. It is designed to be non-thread-safe because requests and responses are matched by a correlation ID in the order they were sent, meaning two callers sharing a connection would read each other's answers (`Connection.kt:23-24`).

## The correlationId matching mechanism [#the-correlationid-matching-mechanism]

To ensure data integrity in a pipelined environment, the connection uses a `correlationId` that is incremented for every request (`BooblikConnection.kt:108`). Because the broker is guaranteed to answer in strict FIFO order, the client uses a `ConcurrentLinkedQueue` of `Pending` objects to match responses to their original callers (`BooblikConnection.kt:60`). If a response arrives with a correlation ID that does not match the head of the queue, the client fails loudly to prevent delivering the wrong data to a caller (`BooblikConnection.kt:35-37`).

## AckPolicy.NONE behavior [#ackpolicynone-behavior]

When using `AckPolicy.NONE`, the `produce` function returns `null` immediately (`Connection.kt:59`). This is because no response is expected from the broker, and since no offset exists until the writer actually reaches the batch on the broker side, there is no answer to wait for (`Connection.kt:44-45`). In this mode, the request is enqueued to the outbound channel but no `Pending` object is registered to wait for a result (`BooblikConnection.kt:111`).

## Topic and Partition routing [#topic-and-partition-routing]

Routing is handled through the `Topic` class, which resolves partitions using metadata fetched from the broker (`Connection.kt:145`). Partition selection follows these rules:

| Key Type    | Logic                                                                                    |
| ----------- | ---------------------------------------------------------------------------------------- |
| `null`      | Round-robin selection where the counter advances on every call (`Connection.kt:192-193`) |
| `ByteArray` | Deterministic selection via `Partitioner.Fnv1a` (`Connection.kt:196`)                    |

## Frame assembly and partial reads [#frame-assembly-and-partial-reads]

The client is designed to handle fragmented network traffic where a single frame might arrive across multiple packets. The `readFrame` function in `Socket.kt:123` first reads a length prefix and then continues to call `recv` until the exact number of bytes requested is received (`Socket.kt:137-140`). Tests in `PartialFrameTest.kt:40-60` verify that frames split across two packets or even delivered one byte at a time are correctly assembled into a single request.

## Connection failure and error handling [#connection-failure-and-error-handling]

The connection lifecycle includes several error states:

* **Absurd Frame Lengths**: If a broker sends a frame length exceeding `Protocol.MAX_FRAME_BYTES`, a `ConnectionException` is thrown to prevent massive memory allocations (`Connection.kt:168-170`).
* **Unknown API Keys**: If a header is valid but the API key is unknown, the broker responds with `UNSUPPORTED_VERSION` while still echoing the `correlationId` (`ErrorCodeTest.kt:37-39`).
* **Socket Closures**: If the socket closes mid-frame, the client throws a `ConnectionException` indicating how many bytes were still expected (`Socket.kt:137-138`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                             | Lines    | What is there                                                          |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------------------------------------------------------------------- |
| [`…/native/Connection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt#L30-L156 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Connection.kt")                  | `30-156` | The `BooblikConnection` class implementing the synchronous protocol.   |
| [`…/client/BooblikConnection.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt#L50-L241 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikConnection.kt") | `50-241` | The pipelined, coroutine-based `BooblikConnection` implementation.     |
| [`…/native/Socket.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt#L42-L148 "booblik-native/src/nativeMain/kotlin/ru/workinprogress/booblik/native/Socket.kt")                              | `42-148` | The POSIX `Socket` wrapper for low-level `send` and `recv` operations. |

## Behaviour that inspires [#behaviour-that-inspires]

* `Topic.partitionFor(null)`: Calling this with a null key advances the internal `roundRobin` counter, meaning that even just "asking" for a partition can change the result of the next call (`Connection.kt:192-193`).
* `BooblikConnection.produce`: When `ackPolicy` is `AckPolicy.NONE`, the function returns `null` instead of an empty result, because no offset exists until the batch is written (`Connection.kt:59`).
