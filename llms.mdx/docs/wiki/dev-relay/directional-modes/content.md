# Directional modes (/wiki/dev-relay/directional-modes)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph LR
    subgraph Kafka
        K_Topic[Kafka Topic]
        K_Group[Consumer Group]
    end

    subgraph Relay
        direction_in[Direction: KAFKA_TO_BOOBLIK]
        direction_out[Direction: BOOBLIK_TO_KAFKA]
    end

    subgraph Booblik
        B_Topic[Booblik Topic]
        B_File[FileOffsetStore]
    end

    K_Topic --> direction_in
    direction_in --> B_Topic
    B_Topic --> direction_out
    direction_out --> K_Topic
    B_File -.-> direction_out"
/>

## Directional Configuration and Environment [#directional-configuration-and-environment]

The behavior of the relay is determined by the `Direction` enum, which is parsed from the `RELAY_DIRECTION` environment variable (defaulting to `KAFKA_TO_BOOBLIK`) in `Main.kt:159-163`. The `RelayConfig` class encapsulates all necessary parameters, including bootstrap servers and topic names, which are loaded from the environment in `Main.kt:157-174`.

| Environment Variable | `RelayConfig` Property | Default Value      |
| -------------------- | ---------------------- | ------------------ |
| `RELAY_DIRECTION`    | `direction`            | `KAFKA_TO_BOOBLIK` |
| `BOOBLIK_HOST`       | `brokerHost`           | `127.0.0.1`        |
| `KAFKA_BOOTSTRAP`    | `kafkaBootstrap`       | `kafka:9092`       |
| `KAFKA_TOPIC`        | `kafkaTopic`           | `orders`           |

## Kafka to booblik: Consumer Group and Manual Commit [#kafka-to-booblik-consumer-group-and-manual-commit]

When operating in the `KAFKA_TO_BOOBLIK` direction, the relay acts as a Kafka consumer. To ensure delivery guarantees, `ENABLE_AUTO_COMMIT_CONFIG` is explicitly set to `false` in `Directions.kt:49`. The process follows a strict lifecycle: it polls records, sends them to booblik via a `Producer`, and only after the booblik acknowledgements are received does it call `consumer.commitSync()` in `Directions.kt:71`. This ensures that the Kafka consumer group position only advances once the data is safely in booblik.

## booblik to Kafka: FileOffsetStore and Checkpointing [#booblik-to-kafka-fileoffsetstore-and-checkpointing]

In the `BOOBLIK_TO_KAFKA` direction, the broker does not track the reader's position. Instead, the relay uses a `FileOffsetStore` to persist the state locally on a volume, as seen in `Directions.kt:93`. The `booblikToKafka` function uses a `checkpointing` operator that saves the offset only after the `collector` has successfully handled the batch, ensuring that the `FileOffsetStore` is updated only after the Kafka producer has finished its work (`Directions.kt:112`).

## The Key-to-Partition Mapping [#the-key-to-partition-mapping]

The relay handles Kafka keys differently depending on the direction. In `kafkaToBooblik`, the Kafka record key is passed to `handle.send(record.value(), key = record.key())` in `Directions.kt:66`, which allows the key to influence partition selection in booblik, preserving per-key ordering. However, because the booblik wire format does not contain a field for the key, the key itself is not stored and is lost for any subsequent round trip (`Main.kt:37-42`).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                           | Lines     | What is there                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ------------------------------------------------ |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L83-L86 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                    | `83-86`   | `Direction` enum definition                      |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L142-L153 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                  | `142-153` | `RelayConfig` data class and environment loading |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L38-L77 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt")  | `38-77`   | `kafkaToBooblik` implementation                  |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L89-L124 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt") | `89-124`  | `booblikToKafka` implementation                  |

## Behaviour that surprises [#behaviour-that-surprises]

* The `kafkaToBooblik` function uses `withContext(Dispatchers.IO)` to wrap the blocking `consumer.poll` and `consumer.commitSync` calls to avoid blocking the coroutine scope (`Directions.kt:62, 71`).
* In the `BOOBLIK_TO_KAFKA` direction, the relay uses `kafka.flush()` before calling `stats.observe` to ensure that all buffered records are actually sent to Kafka before the position is updated (`Directions.kt:119`).
* The `RelayConfig` uses `replace` logic for environment variables, where the `direction` string is transformed by converting it to uppercase and replacing hyphens with underscores (`Main.kt:160-162`).
