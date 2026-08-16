# Reconnection logic (/wiki/dev-consumer/reconnection-logic)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer
    participant B as Broker
    participant S as Session/Socket

    C->>B: Connect (Accept)
    B-->>S: Connection Established
    C->>S: Send Fetch Request
    alt Network Failure
        S--xC: Connection Dropped
        C->>C: Catch Exception
        C->>C: Increment Reconnects
        C->>C: Delay 1000ms
        C->>B: Reconnect
    else Session Error
        S-->>C: ErrorCode (e.g., Unknown Topic)
        C->>C: Throw FetchFailedException
    end"
/>

## The `BooblikSubscriber` lifecycle [#the-boobliksubscriber-lifecycle]

The consumer's main loop is designed to be resilient to broker unavailability. As seen in `Main.kt:75-96`, the `while(true)` loop wraps the entire `BooblikSubscriber` lifecycle. If the broker goes away, the `catch (failure: Exception)` block intercepts the error, increments the `reconnects` counter in the `Stats` class (`Main.kt:93`), and waits for 1000ms before attempting to re-establish the connection. This ensures that a broker restart does not crash the consumer service, as the consumer maintains its state via the `FileOffsetStore` (`Main.kt:60`).

## The `BooblikConnection` and `Session` failure modes [#the-booblikconnection-and-session-failure-modes]

The system distinguishes between different levels of failure to provide better diagnostic data. While `Consumer.kt:89-91` shows that a `poll()` can throw a `FetchFailedException` when the broker returns a non-zero `ErrorCode`, there are deeper failures. A `Session` might die due to an exception while a request is being handled, which is a distinct event from a client simply leaving a connection. This distinction is critical for identifying bugs in the server's request handling logic (`Metrics.kt:55-67`).

## The `lastAcceptFailure` and `lastSessionFailure` metrics [#the-lastacceptfailure-and-lastsessionfailure-metrics]

The broker provides specific metrics to help operators distinguish between "the broker is unreachable" and "the broker is running but failing to handle requests." According to `Metrics.kt:45-67`, the `lastAcceptFailure` captures exceptions occurring in the `accept` loop, which can lead to a "silent death" where the process is alive but no new connections can be made. Meanwhile, `lastSessionFailure` captures exceptions that kill a specific session while it is actively holding a request, providing evidence for intermittent failures that would otherwise be invisible (`Metrics.kt:55-67`).

## The `SessionStressTest` pipelined exchange [#the-sessionstresstest-pipelined-exchange]

To ensure the stability of the pipelined exchange, `SessionStressTest.kt:27-52` runs a high-volume loop of produce and receive operations. This test is designed to catch rare, non-deterministic failures (like the "M-64" issue) by repeating the exchange hundreds of times. A key concern during these tests is ephemeral port exhaustion; if the test environment runs out of local ports due to many connections stuck in `TIME_WAIT`, the test will fail not because of a broker bug, but because the harness has exhausted the OS resources (`SessionStressTest.kt:97-101`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                     | Lines   | What is there                                                      |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------------ |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L75-L96 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")               | `75-96` | The infinite retry loop and reconnection delay logic.              |
| [`…/net/Metrics.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt#L45-L67 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Metrics.kt")                               | `45-67` | Volatile fields for tracking the last accept and session failures. |
| [`…/client/Consumer.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt#L89-L91 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Consumer.kt")     | `89-91` | Logic for throwing `FetchFailedException` on broker errors.        |
| [`…/net/SessionStressTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SessionStressTest.kt#L27-L52 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SessionStressTest.kt") | `27-52` | The stress test harness for repeated connection cycles.            |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Accept Failures**: If an exception occurs in the `accept` loop, the broker might continue to run and hold its port, but it will stop accepting any new connections. This is tracked via `lastAcceptFailure` in `Metrics.kt:50`.
* **At-Least-Once via Checkpointing**: The `checkpointing` mechanism in `Main.kt:83` is intentionally placed after the `collect` block. This means if a crash occurs after processing a batch but before saving the offset, the batch will be replayed upon restart.
* **The `RecordExceedsMaxBytesException`**: A consumer might stop making progress if a single record is larger than the `maxBytes` configured in `Consumer.kt:28-35`. This is a client-side limit that results in a permanent failure to advance the position.
