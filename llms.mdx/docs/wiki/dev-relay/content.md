# dev/relay (/wiki/dev-relay)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 3. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph LR
    subgraph Kafka
        K[Kafka Topic]
    end
    subgraph Booblik
        B[Booblik Topic]
    end

    K -- &#x22;KAFKA_TO_BOOBLIK&#x22; --> B
    B -- &#x22;BOOBLIK_TO_KAFKA&#x22; --> K"
/>

## Directional modes [#directional-modes]

The relay operates in one of two modes defined in `Main.kt:83-86`:

| Mode               | Description                      | Position Ownership                                         |
| ------------------ | -------------------------------- | ---------------------------------------------------------- |
| `KAFKA_TO_BOOBLIK` | Moves data from Kafka to booblik | Managed by Kafka via Consumer Groups (`Directions.kt:46`)  |
| `BOOBLIK_TO_KAFKA` | Moves data from booblik to Kafka | Managed locally via `FileOffsetStore` (`Directions.kt:93`) |

More: [Directional modes](dev-relay/directional-modes)

## booblikToKafka [#boobliktokafka]

This mode consumes data from booblik and produces it to Kafka. Because the booblik broker does not remember reader positions, the relay uses a `FileOffsetStore` to manage state locally on the relay's volume (`Directions.kt:93`). To ensure at-least-once delivery, the producer is configured with `acks=all` (`Directions.kt:102`).

More: [booblikToKafka](dev-relay/boobliktokafka)

## At-least-once delivery guarantees [#at-least-once-delivery-guarantees]

The relay implements at-least-once delivery by ensuring the position is only updated after the destination has acknowledged the data. In `kafkaToBooblik`, `consumer.commitSync()` is called only after all records in a batch are sent to booblik (`Directions.kt:71`). In `booblikToKafka`, the `checkpointing` mechanism saves the position only after the `kafka.flush()` operation is complete (`Directions.kt:119`).

## RelayStats [#relaystats]

The monitoring surface is exposed via an HTTP server and provides real-time metrics through the `Stats` class (`Main.kt:88`). The `RelayStats` data class (`Main.kt:129`) includes:

* `relayed`: Total count of relayed records.
* `batches`: Total number of batches processed.
* `position`: The last observed offset/position.
* `restarts`: Number of times the relay loop has restarted due to failures.
* `lastFailure`: The message of the last encountered exception.
* `lastRecord`: A 120-character sample of the last record.

## RelayConfig [#relayconfig]

The relay is configured via environment variables as defined in `Main.kt:142-176`. The following parameters are available:

| Parameter        | Environment Variable | Default Value      |
| ---------------- | -------------------- | ------------------ |
| `name`           | `RELAY_NAME`         | `relay`            |
| `direction`      | `RELAY_DIRECTION`    | `KAFKA_TO_BOOBLIK` |
| `brokerHost`     | `BOOBLIK_HOST`       | `127.0.0.1`        |
| `brokerPort`     | `BOOBLIK_PORT`       | `9092`             |
| `booblikTopic`   | `BOOBLIK_TOPIC`      | `mirrored`         |
| `kafkaBootstrap` | `KAFKA_BOOTSTRAP`    | `kafka:9092`       |
| `kafkaTopic`     | `KAFKA_TOPIC`        | `orders`           |
| `kafkaGroup`     | `KAFKA_GROUP`        | `booblik-relay`    |
| `stateDir`       | `STATE_DIR`          | `/var/lib/relay`   |
| `httpPort`       | `HTTP_PORT`          | `8080`             |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                           | Lines     | What is there                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ----------------------------------------------------------- |
| [`…/relay/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/build.gradle.kts#L1-L9 "dev/relay/build.gradle.kts")                                                                                                   | `1-9`     | Dependencies for Kafka, Ktor, and Booblik client.           |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L38-L77 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt")  | `38-77`   | Implementation of the `kafkaToBooblik` function.            |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L89-L124 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt") | `89-124`  | Implementation of the `booblikToKafka` function.            |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L47-L81 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                    | `47-81`   | The `main` entry point and the execution loop.              |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L88-L127 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                   | `88-127`  | The `Stats` class for tracking relay metrics.               |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L142-L176 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                  | `142-176` | The `RelayConfig` data class and environment loading logic. |

## Behaviour that surprise [#behaviour-that-surprise]

* The `kafkaToBooblik` function uses `consumer.commitSync()` (`Directions.kt:71`) to ensure that a crash between sending data and committing the offset results in a repeated batch rather than data loss.
* The `booblikToKafka` function requires an explicit `kafka.flush()` (`Directions.kt:119`) before the `checkpointing` mechanism updates the local `FileOffsetStore` to maintain delivery guarantees.
* The `main` function (`Main.kt:47`) uses a `while(true)` loop with a `try-catch` block to ensure that the relay automatically restarts if a connection to either Kafka or booblik is lost.

## More [#more]

* [kafkaToBooblik](dev-relay/kafkatobooblik)
