# dev/consumer (/wiki/dev-consumer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start] --> B[Load ConsumerConfig]
    B --> C[Load Offset from FileOffsetStore]
    C --> D{Connection Loop}
    D -->|Success| E[BooblikSubscriber]
    E --> F[Collect Records]
    F --> G[Handle Record]
    G --> H[Checkpoint Offset to Disk]
    H --> F
    E -->|Error| I[Increment Reconnects]
    I -->|Delay 1s| D
    D -->|Shutdown| J[End]"
/>

## ConsumerConfig [#consumerconfig]

Configuration parameters are loaded from environment variables to define how the consumer connects to the broker and where it stores its state, as implemented in [`Main.kt:160-181`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L160-L181).

| Environment Variable | Default Value       | Description                       |
| -------------------- | ------------------- | --------------------------------- |
| `CONSUMER_NAME`      | `consumer`          | Name of the consumer instance     |
| `BOOBLIK_HOST`       | `127.0.0.1`         | Broker host address               |
| `BOOBLIK_PORT`       | `9092`              | Broker port                       |
| `BOOBLIK_TOPIC`      | `events`            | The topic to subscribe to         |
| `BOOBLIK_PARTITION`  | `0`                 | The specific partition to consume |
| `STATE_DIR`          | `/var/lib/consumer` | Directory for `FileOffsetStore`   |
| `HTTP_PORT`          | `8080`              | Port for the metrics HTTP server  |

## consumeForever [#consumeforever]

The main execution loop, implemented in [`Main.kt:56-97`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L56-L97), maintains a continuous connection to the broker. It uses a `while(true)` loop to ensure that if a `BooblikSubscriber` fails or the broker becomes unavailable, the consumer attempts to re-establish the subscription.

More: [consumeForever](dev-consumer/consumeforever)

## Recovery after a crash [#recovery-after-a-crash]

To ensure at-least-once delivery, the consumer uses `FileOffsetStore` to persist the last successfully processed offset. Upon startup, `consumeForever` checks for a saved offset in [`Main.kt:67-68`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L67-L68); if found, it resumes from that position; otherwise, it starts from `StartPosition.Earliest`. The checkpointing mechanism is applied via `.checkpointing(store)` in [`Main.kt:83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L83), ensuring the offset is saved only after the batch has been processed.

## Reconnection logic [#reconnection-logic]

Connectivity issues are handled within a `try-catch` block inside the loop in [`Main.kt:76-95`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L76-L95). If an exception occurs (such as the broker going away), the consumer increments the `reconnects` counter, waits for a 1000ms delay, and then attempts to reconnect.

More: [Reconnection logic](dev-consumer/reconnection-logic)

## Stats and monitoring [#stats-and-monitoring]

The service exposes operational metrics through an embedded Ktor server configured in [`Main.kt:46-52`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L46-L52). The `Stats` class tracks the number of handled records, the current position, the lag, and the number of reconnection attempts, which are then serialized to JSON via the `/stats` endpoint.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                         | Lines     | What is there                                                       |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------- |
| [`…/consumer/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/build.gradle.kts#L1-L8 "dev/consumer/build.gradle.kts")                                                                                        | `1-8`     | Dependency declarations for Ktor, Booblik client, and serialization |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L38-L54 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")   | `38-54`   | The `main` function entry point and server setup                    |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L56-L97 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")   | `56-97`   | The `consumeForever` loop and subscription logic                    |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L109-L145 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt") | `109-145` | The `Stats` class and `ConsumerStats` data class                    |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L160-L181 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt") | `160-181` | The `ConsumerConfig` data class and environment loading             |

## Behaviour that surprise [#behaviour-that-surprise]

* The `checkpointing` function in `consumeForever` is designed such that the offset is saved **after** the `collect` block finishes, which guarantees at-least-once delivery by replaying batches if a crash occurs before the save is completed ([`Main.kt:80-82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L80-L82)).
* The `StartPosition.Earliest` setting used in `consumeForever` refers to the start of the **live** log (the data currently retained by the broker) rather than the absolute beginning of the log history ([`Main.kt:64-66`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L64-L66)).
