# Transport (/wiki/booblik-net/transport)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant S as BooblikServer
    participant L as SelectorLoop
    participant V as VirtualThreadExecutor

    alt Transport.SELECTOR
        C->>S: TCP Connection
        S->>L: register(channel)
        L->>L: select()
        L->>S: resume(continuation)
        S->>C: serve(SelectorConnection)
    else Transport.VIRTUAL_THREADS
        C->>S: TCP Connection
        S->>V: executor.submit { runBlocking { serve(...) } }
        V->>C: BlockingConnection.readFully()
    end"
/>

## Transport modes [#transport-modes]

The server supports two distinct transport mechanisms, which are configured via `ServerConfig` in [`BooblikServer.kt:45-64`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L45-L64).

| Mode              | Mechanism                                                                   | Implementation       |
| ----------------- | --------------------------------------------------------------------------- | -------------------- |
| `SELECTOR`        | Non-blocking NIO with a dedicated selector thread and coroutine suspension. | `SelectorConnection` |
| `VIRTUAL_THREADS` | Blocking sockets where each session runs on a virtual thread.               | `BlockingConnection` |

## SelectorLoop mechanics [#selectorloop-mechanics]

The `SelectorLoop` is the engine that turns NIO readiness into coroutine suspension. It operates on a dedicated thread to avoid deadlocks during `SelectionKey.register` calls on certain JDK implementations ([`SelectorLoop.kt:48-55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L48-L55)).

To ensure thread safety, interest changes are not applied directly from other threads. Instead, they are posted to a `ConcurrentLinkedQueue` and applied by the loop thread itself ([`SelectorLoop.kt:94-96`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L94-L96)). Every change triggers a `selector.wakeup()` to ensure the loop processes the pending actions immediately ([`SelectorLoop.kt:95-96`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L95-L96)).

## SelectorConnection and suspension [#selectorconnection-and-suspension]

`SelectorConnection` implements the `Connection` interface by using the `SelectorLoop` to suspend coroutines when I/O is not ready. When `readFully` or `writeFully` encounters a zero-byte operation, it calls `loop.awaitReadable(key)` or `loop.awaitWritable(key)` ([`Connection.kt:65-75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L65-L75)). This suspends the session coroutine, freeing up the thread until the `SelectorLoop` detects readiness and resumes the `CancellableContinuation` ([`SelectorLoop.kt:80-81`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L80-L81)).

## BlockingConnection and virtual threads [#blockingconnection-and-virtual-threads]

`BlockingConnection` serves as the baseline for performance measurements ([`Connection.kt:100-102`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L100-L102)). While virtual threads make blocking code efficient, they do not virtualize disk I/O. Consequently, a `transferTo` call that triggers a page fault can pin the carrier thread to the disk, potentially stalling the executor ([`Connection.kt:104-106`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L104-L106)).

## PartialFrame assembly [#partialframe-assembly]

The protocol requires full frames to be assembled from the stream. The `PartialFrameTest` verifies that the selector correctly handles frames split across multiple packets ([`PartialFrameTest.kt:40-56`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L40-L56)) and even frames delivered one byte at a time ([`PartialFrameTest.kt:66-76`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L66-L76)). This ensures the `SelectorConnection` correctly waits for more data via `loop.awaitReadable` when a `read` returns zero ([`Connection.kt:66`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L66)).

## Connection failure and frame corruption [#connection-failure-and-frame-corruption]

The server is designed to be resilient to malformed data. If a client sends an unserviceable request (e.g., an unsupported version), the broker responds with an `ErrorCode` but keeps the connection alive ([`PartialFrameTest.kt:80-109`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L80-L109)). However, an "absurd" frame length—such as a request claiming a massive size—is treated as a fatal error that drops the connection to protect the broker's memory ([`PartialFrameTest.kt:112-129`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L112-L129)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                   | Lines    | What is there                                          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------ |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L25-L34 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")           | `25-34`  | `Transport` enum defining the two execution models.    |
| [`…/nio/Connection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L55-L142 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt")           | `55-142` | `Connection` interface and its two implementations.    |
| [`…/nio/SelectorLoop.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L31-L41 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt")      | `31-41`  | The dedicated thread and management of the `Selector`. |
| [`…/net/PartialFrameTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L29-L175 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt") | `29-175` | Tests for split packets and frame assembly.            |

## Behaviour that surprises [#behaviour-that-surprises]

* **Interest changes are asynchronous:** Because `SelectionKey.interestOps` can block or be ineffective during a `select()` call, `SelectorLoop` must queue changes and use `selector.wakeup()` to apply them safely ([`SelectorLoop.kt:26-28`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L26-L28)).
* **Disk I/O pins carrier threads:** Even when using virtual threads, `BlockingConnection` can cause a carrier thread to stall if `transferTo` encounters a page fault during disk I/O ([`Connection.kt:104-106`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L104-L106)).
* **Zero-copy requires the raw channel:** Wrapping a `SocketChannel` in a decorator can silently turn a `sendfile` path into a copy loop, making `transferTarget` a critical property for performance ([`Connection.kt:28-30`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L28-L30)).
