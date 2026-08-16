# kafkaToBooblik (/wiki/dev-relay/kafkatobooblik)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `kafkaToBooblik` function acts as a bridge that translates data from a Kafka topic into the booblik format. It consumes batches of records from Kafka and pushes them into a booblik topic, ensuring that the data is safely acknowledged by the booblik broker before the Kafka consumer advances its position.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant K as Kafka Consumer
    participant R as Relay (kafkaToBooblik)
    participant B as Booblik Broker
    participant S as Stats

    loop Continuous Polling
        K->>R: poll(Duration)
        R->>B: handle.send(value, key)
        B-->>R: acknowledgements (async)
        R->>R: await() all acknowledgements
        R->>K: commitSync()
        R->>S: observe(count, lastOffset, lastValue)
    end"
/>

## KafkaConsumer configuration and properties [#kafkaconsumer-configuration-and-properties]

The Kafka consumer is configured to ensure manual control over the message lifecycle. It uses `ByteArrayDeserializer` for both keys and values to treat all data as raw bytes [`Directions.kt:47-48`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L47-L48). Crucially, `ENABLE_AUTO_COMMIT_CONFIG` is set to `false` to prevent the consumer from automatically advancing offsets before the data is safely stored in booblik [`Directions.kt:49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L49). The `AUTO_OFFSET_RESET_CONFIG` is set to `earliest` to ensure that a new relay can catch up on all existing data in the topic [`Directions.kt:51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L51).

## The at-least-once delivery guarantee [#the-at-least-once-delivery-guarantee]

The relay implements an at-least-once delivery guarantee through a strict sequence of operations in the polling loop. First, it maps the records to `handle.send` calls, which are asynchronous `acknowledgements` [`Directions.kt:66`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L66). It then calls `await()` on these acknowledgements to ensure every record in the batch has been processed by the booblik broker [`Directions.kt:67`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L67). Only after all records are acknowledged does it call `consumer.commitSync()` [`Directions.kt:71`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L71). This order ensures that if a crash occurs between sending and committing, the batch will be re-delivered upon restart [`Directions.kt:70`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L70).

## Per-key ordering preservation [#per-key-ordering-preservation]

Even though the booblik wire protocol does not store Kafka keys [`research-usecases.md:37-41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/research/research-usecases.md#L37-L41), the relay preserves per-key ordering during the crossing. When `handle.send(record.value(), key = record.key())` is called, the Kafka key is used to determine the target booblik partition [`Directions.kt:66`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L66). Because the key determines the partition, all records with the same key are routed to the same booblik partition, maintaining their relative order [`Directions.kt:34-35`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L34-L35).

## The relay restart loop and error handling [#the-relay-restart-loop-and-error-handling]

The relay is designed to be resilient against transient failures on either side of the connection. The main loop is wrapped in a `while (true)` block within a `CoroutineScope` initialized with a `SupervisorJob` [`Main.kt:54-55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L54-L55). If an exception occurs, the error is caught, logged, and the loop restarts after a 2-second delay [`Main.kt:61-68`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L61-L68). This prevents the relay from dying permanently if one side (Kafka or booblik) experiences a temporary outage [`Main.kt:62-63`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L62-L63).

## Relay statistics and observability [#relay-statistics-and-observability]

Observability is provided through the `Stats` class, which tracks the progress of the relay. The `observe` function updates several metrics whenever a batch is processed [`Main.kt:102-111`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L102-L111).

| Metric       | Description                                          |
| ------------ | ---------------------------------------------------- |
| `relayed`    | Total number of records processed                    |
| `batches`    | Total number of batches processed                    |
| `restarts`   | Total number of times the relay loop has restarted   |
| `position`   | The last processed offset/value                      |
| `lastRecord` | A string sample (up to 120 chars) of the last record |

The `RelayStats` data class provides a serializable snapshot of these metrics for the HTTP `/stats` endpoint [`Main.kt:129-140`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L129-L140).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                          | Lines   | What is there                                                            |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------------------ |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L38-L77 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt") | `38-77` | Implementation of the `kafkaToBooblik` function and its logic.           |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L47-L81 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                   | `47-81` | The `main` function, the `Stats` class, and the `RelayStats` data class. |

## Behaviour that surprises [#behaviour-that-surprises]

* **Key Loss**: While `handle.send` uses the key to maintain partition ordering, the key itself is not stored in booblik, meaning it cannot be recovered when reading from booblik back to Kafka [`research-usecases.md:37-41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/research/research-usecases.md#L37-L41).
* **At-least-once Duplicates**: Because `consumer.commitSync()` happens after the booblik write, a crash between these two steps results in the same records being sent again upon restart, making duplicates a legal and expected behavior [`Directions.kt:70-72`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L70-L72).
