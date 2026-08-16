# Main execution lifecycle (/wiki/booblik-app/main-execution-lifecycle)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant Main
    participant Config
    participant Server
    participant Broker
    participant Background
    participant OS

    Main->>Config: load(args)
    Main->>Broker: open(dir, partitions, config)
    Main->>Server: start()
    Server-->>Main: address
    Main->>Background: launch(reportMetrics)
    Main->>Background: launch(applyRetention)
    Main->>OS: addShutdownHook
    Note over OS: User triggers shutdown
    OS->>Server: close()
    OS->>Background: cancel()
    OS->>Broker: close()
    OS->>Main: stopped.countDown()"
/>

## BooblikServer startup and transport selection [#booblikserver-startup-and-transport-selection]

The server initialization begins by binding a `ServerSocketChannel` to the configured address and port [`BooblikServer.kt:134-140`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L134-L140). Once bound, the server selects a transport mechanism based on the `Transport` enum [`BooblikServer.kt:25-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L25-L34):

| Transport         | Description                                                       |
| ----------------- | ----------------------------------------------------------------- |
| `SELECTOR`        | Uses a dedicated selector loop with one coroutine per connection. |
| `VIRTUAL_THREADS` | Uses blocking sockets with one virtual thread per connection.     |

## The acceptor loop and connection handling [#the-acceptor-loop-and-connection-handling]

The acceptor mechanism differs by transport. In `SELECTOR` mode, a coroutine runs a loop that calls `serverChannel.accept()` [`BooblikServer.kt:164-175`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L164-L175), where transient exceptions are caught and logged to metrics to prevent the loop from dying. In `VIRTUAL_THREADS` mode, a platform thread runs the acceptor loop [`BooblikServer.kt:204-224`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L204-L224). To ensure that a single failing client session does not crash the entire server, `BooblikServer` uses a `SupervisorJob` [`BooblikServer.kt:123`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L123), which isolates failures to the specific coroutine scope of that session.

## Session lifecycle and FetchMode execution [#session-lifecycle-and-fetchmode-execution]

A `Session` is established when a connection is accepted and configured [`BooblikServer.kt:182`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L182). The `FetchMode` determines how data is transferred from the log to the client [`BooblikServer.kt:37-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L37-L43):

| FetchMode   | Mechanism                                                                  |
| ----------- | -------------------------------------------------------------------------- |
| `ZERO_COPY` | Uses `transferTo` to move data from the page cache directly to the socket. |
| `HEAP`      | Reads data into a heap buffer before writing.                              |

## Graceful shutdown and the order of closure [#graceful-shutdown-and-the-order-of-closure]

The shutdown process is triggered by a JVM shutdown hook that ensures a specific sequence of closure to prevent data loss [`Main.kt:91-100`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L91-L100). The order is strictly:

1. `server.close()` to stop accepting new connections.
2. `background.cancel()` to stop background tasks like retention.
3. `broker.close()` to ensure all writers finish flushing batches to disk before the underlying log is closed.

## Smoke tests and distribution verification [#smoke-tests-and-distribution-verification]

The `ci/smoke.sh` script performs a full end-to-end verification of the distribution [`smoke.sh:30-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L30-L34). It builds the `installDist` distribution, starts the broker, and uses a Python script to perform `PRODUCE` and `FETCH` operations over the wire. This verifies that the broker can recover its state from disk after a restart [`smoke.sh:130-142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L130-L142) and that the wire format (including CRC32C checksums) is correctly implemented.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                           | Lines     | What is there                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ------------------------------------------------------------------------------- |
| [`…/app/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L33-L104 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt")                             | `33-104`  | The main entry point, orchestration of server/broker, and shutdown hooks.       |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L117-L260 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt") | `117-260` | The network server implementation, transport selection, and session management. |
| [`ci/smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L30-L144 "ci/smoke.sh")                                                                                                                                           | `30-144`  | The smoke test script for distribution and protocol verification.               |

## Behaviour that surprising [#behaviour-that-surprising]

* The `BooblikServer` uses a `SupervisorJob` [`BooblikServer.kt:123`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L123) specifically so that a client sending "nonsense" only kills its own session and not the entire server.
* The `applyRetention` function [`Main.kt:135`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L135) is the only place that decides *when* retention happens, as the `Broker` itself has no internal clock.
* The `booblik.metrics.interval.millis` setting [`Main.kt:118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L118) allows disabling metrics by setting it to `0`, which results in silent operation rather than an error.
