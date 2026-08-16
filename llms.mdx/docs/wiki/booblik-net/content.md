# booblik-net (/wiki/booblik-net)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Client -->|TCP| Server[BooblikServer]
    Server -->|Transport| Conn[Connection]
    Conn -->|Selector| SL[SelectorLoop]
    Conn -->|Virtual Threads| BC[BlockingConnection]
    Server -->|Manages| Broker[Broker]
    Broker -->|Contains| PR[PartitionRegistry]
    PR -->|Holds| PH[PartitionHandle]
    PH -->|Writes to| PL[PartitionLog]
    Server -->|Reports| M[Metrics]"
/>

## BooblikServer [#booblikserver]

The network front end and server lifecycle management. It manages the `ServerSocketChannel` and orchestrates the startup of either a selector-based or virtual-thread-based transport ([`BooblikServer.kt:117-147`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L117-L147)).

More: [BooblikServer](booblik-net/booblikserver)

## Transport [#transport]

Comparison between SELECTOR and VIRTUAL\_THREADS readiness mechanisms.

| Transport         | Description                                                                                                                                                                                                                                           |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SELECTOR`        | Own selector loop, one coroutine per connection ([`BooblikServer.kt:26-27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L26-L27))     |
| `VIRTUAL_THREADS` | Blocking sockets, one virtual thread per connection ([`BooblikServer.kt:30-33`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L30-L33)) |

More: [Transport](booblik-net/transport)

## FetchMode [#fetchmode]

The ZERO\_COPY and HEAP strategies for response delivery.

| FetchMode   | Description                                                                                                                                                                                                                                               |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ZERO_COPY` | `sendfile`: page cache to socket buffer, never through the JVM ([`BooblikServer.kt:39`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L39)) |
| `HEAP`      | Read into a heap buffer and write it ([`BooblikServer.kt:42`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L42))                           |

More: [FetchMode](booblik-net/fetchmode)

## Connection [#connection]

The abstraction layer for `SelectorConnection` and `BlockingConnection`. It defines how data is read, written, and transferred from segments to the socket ([`Connection.kt:23-53`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L23-L53)).

## SelectorLoop [#selectorloop]

The NIO readiness engine and the mechanism for queuing interest changes. It uses a dedicated thread to process `SelectionKey` readiness and handles interest changes via a `ConcurrentLinkedQueue` to avoid blocking the selector ([`SelectorLoop.kt:31-40`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L31-L40)).

## Broker [#broker]

Management of partitions, retention policies, and the partition registry. It is responsible for opening partitions from a directory and applying retention policies ([`Broker.kt:49-81`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt#L49-L81)).

## Metrics [#metrics]

Telemetry for network counters, session failures, and partition snapshots. It provides a `snapshot` function to capture the current state of the broker and its partitions ([`Metrics.kt:29-145`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt#L29-L145)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                              | Lines     | What is there                     |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------- |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L45-L64 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")      | `45-64`   | `ServerConfig` definition         |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L67-L70 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")      | `67-70`   | `PartitionHandle` definition      |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L79-L107 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")     | `79-107`  | `PartitionRegistry` definition    |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L117-L121 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")    | `117-121` | `BooblikServer` class declaration |
| [`…/nio/Connection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L23-L53 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt")       | `23-53`   | `Connection` interface            |
| [`…/nio/Connection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L56-L97 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt")       | `56-97`   | `SelectorConnection` class        |
| [`…/nio/Connection.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L108-L142 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt")     | `108-142` | `BlockingConnection` class        |
| [`…/nio/SelectorLoop.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L31-L40 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt") | `31-40`   | `SelectorLoop` class              |
| [`…/net/Broker.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt#L19-L28 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt")                           | `19-28`   | `BrokerConfig` definition         |
| [`…/net/Broker.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt#L49-L54 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt")                           | `49-54`   | `Broker` class declaration        |
| [`…/net/Metrics.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt#L29-L48 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt")                        | `29-48`   | `Metrics` class declaration       |

## Public API [#public-api]

| What                 | Where                                                                                                                                                                                      | Why                                                              |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| `ServerConfig`       | [`BooblikServer.kt:45`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L45)   | Configuration for the server transport and fetch modes.          |
| `PartitionHandle`    | [`BooblikServer.kt:67`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L67)   | Holds the log and writer for a specific partition.               |
| `PartitionRegistry`  | [`BooblikServer.kt:79`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L79)   | A registry to find partition handles by topic and ID.            |
| `BooblikServer`      | [`BooblikServer.kt:117`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L117) | The main server entry point for starting and closing the broker. |
| `Connection`         | [`Connection.kt:23`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L23)     | Abstraction for reading and writing to a client.                 |
| `SelectorConnection` | [`Connection.kt:56`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L56)     | A non-blocking connection implementation using a `SelectorLoop`. |
| `BlockingConnection` | [`Connection.kt:108`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L108)   | A blocking connection implementation for virtual threads.        |
| `SelectorLoop`       | [`SelectorLoop.kt:31`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L31) | The engine that manages NIO readiness.                           |
| `BrokerConfig`       | [`Broker.kt:19`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt#L19)                 | Configuration for segmenting and retention in the broker.        |
| `Broker`             | [`Broker.kt:49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Broker.kt#L49)                 | The high-level broker managing partitions and retention.         |
| `Metrics`            | [`Metrics.kt:29`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt#L29)               | Provides telemetry and snapshots of broker activity.             |

## Behaviour that surprises [#behaviour-that-surprises]

* `SelectorLoop` uses a `ConcurrentLinkedQueue` to post interest changes because modifying `SelectionKey.interestOps` from a thread other than the selector thread can be ineffective or block ([`SelectorLoop.kt:26-28`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/SelectorLoop.kt#L26-L28)).
* `BlockingConnection`'s `transferFrom` method may enter a tight loop if `segment.transferTo` returns zero, as it treats zero as "try again immediately" rather than waiting ([`Connection.kt:134`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/nio/Connection.kt#L134)).
* `BooblikServer` uses a `SupervisorJob` for its scope so that a failure in a single client session does not crash the entire server ([`BooblikServer.kt:185-186`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L185-L186)).
