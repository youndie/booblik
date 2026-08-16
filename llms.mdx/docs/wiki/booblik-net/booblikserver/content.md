# BooblikServer (/wiki/booblik-net/booblikserver)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `BooblikServer` acts as the network front end of the broker, managing the lifecycle of connections and dispatching requests to the appropriate partitions. It provides two distinct transport mechanisms and supports zero-copy data transfer for high-performance fetching.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant OS as OS/Network
    participant Acceptor as Acceptor Loop
    participant Session as Session (Coroutine)
    participant Storage as PartitionLog/Writer

    OS->>Acceptor: TCP Connection
    alt Transport.SELECTOR
        Acceptor->>Session: Register in SelectorLoop
    else Transport.VIRTUAL_THREADS
        Acceptor->>Session: Submit to VirtualThreadExecutor
    end
    Session->>Session: Protocol Loop (Session.serve)
    Session->>Storage: FETCH Request
    Storage-->>Session: Data (via FileChannel.transferTo)
    Session->>OS: Write to Socket"
/>

## Transport Modes: SELECTOR vs VIRTUAL\_THREADS [#transport-modes-selector-vs-virtual_threads]

The server supports two different execution models for handling connections, which can be configured via `ServerConfig` (`BooblikServer.kt:59`):

| Mode              | Mechanism               | Implementation Detail                                                                                                            |
| ----------------- | ----------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `SELECTOR`        | NIO-based selector loop | Uses `SelectorLoop` to manage readiness and one coroutine per connection (`BooblikServer.kt:150-188`)                            |
| `VIRTUAL_THREADS` | Thread-per-connection   | Uses a `VirtualThreadPerTaskExecutor` where `runBlocking` turns suspension points into thread parks (`BooblikServer.kt:200-218`) |

## FetchMode: ZERO\_COPY and the FileChannel Path [#fetchmode-zero_copy-and-the-filechannel-path]

To achieve high performance, the server provides two ways to deliver data to the client (`BooblikServer.kt:37-43`):

| Mode        | Mechanism   | Description                                                                                                                                       |
| ----------- | ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ZERO_COPY` | `sendfile`  | Uses `FileChannel.transferTo` to move data from the page cache directly to the socket buffer, bypassing the JVM heap (`BooblikServer.kt:113-115`) |
| `HEAP`      | Heap Buffer | Reads data into a heap buffer and then writes it, serving as the control group for experiments (`BooblikServer.kt:41-42`)                         |

## The Acceptor Loop and Connection Lifecycle [#the-acceptor-loop-and-connection-lifecycle]

The server maintains an acceptor loop that is responsible for accepting new `SocketChannel` instances. In `SELECTOR` mode, this loop is a coroutine running within a `SupervisorJob` (`BooblikServer.kt:154-159`). The loop is designed to be resilient: if an exception occurs during `serverChannel.accept()`, it is treated as transient, the error is recorded in `metrics`, and the loop continues to prevent the broker from hanging silently (`BooblikServer.kt:169-175`).

## Session Failure Domains and SupervisorJob [#session-failure-domains-and-supervisorjob]

To ensure high availability, each client connection is isolated within its own coroutine scope. By using a `SupervisorJob` (`BooblikServer.kt:123-124`), the server ensures that a failure in a single `Session` (such as a malformed request or a sudden connection drop) only terminates that specific session and does not propagate upwards to crash the entire `BooblikServer` (`BooblikServer.kt:185-187`).

## The M-64 Bind Address Fix [#the-m-64-bind-address-fix]

A critical correctness feature is the ability to bind to a specific `bindAddress` rather than a wildcard address (`BooblikServer.kt:58`). As documented in `ServerFixture.kt:40-46`, binding to a specific address like `127.0.0.1` prevents "silent connection theft" on BSD-derived systems where `SO_REUSEADDR` might allow another process to intercept connections intended for the broker if a wildcard bind is used.

## Server Configuration and TCP Options [#server-configuration-and-tcp-options]

The server applies specific socket options to optimize the transport layer (`BooblikServer.kt:227-233`):

| Option         | Purpose                                                                                                                                                                       |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `TCP_NODELAY`  | Disables Nagle's algorithm to reduce latency for small batches (`BooblikServer.kt:228`)                                                                                       |
| `SO_KEEPALIVE` | Enabled to ensure that connections held open for long periods (e.g., during a `FETCH`) are detected as failed by the OS rather than remaining silent (`BooblikServer.kt:232`) |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                           | Lines     | What is there                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ------------------------------------------------ |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L25-L64 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")   | `25-64`   | `ServerConfig` and `Transport`/`FetchMode` enums |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L117-L147 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `117-147` | `BooblikServer` class and `start()` method       |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L149-L197 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `149-197` | `startSelector` implementation and acceptor loop |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L199-L225 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `199-225` | `startVirtualThreads` implementation             |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L235-L251 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `235-251` | `serve` method and session lifecycle             |

## Behaviour that surprises [#behaviour-that-surprises]

* The `startSelector` loop is designed to catch `Exception` and continue (`BooblikServer.kt:169`) to avoid a "silent hang" where the process is alive but the port is unresponsive.
* In `startVirtualThreads`, the code uses `runBlocking` inside a virtual thread (`BooblikServer.kt:218`) specifically to allow the same `serve` logic to be used for both `SELECTOR` and `VIRTUAL_THREADS` transports.
* The `close` method performs a coordinated shutdown of the `serverChannel`, `acceptorThread`, `virtualThreads`, and the `scope` (`BooblikServer.kt:253-260`).
