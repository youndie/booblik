# booblikToKafka (/wiki/dev-relay/boobliktokafka)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblikToKafka` relay acts as a bridge that consumes data from a booblik topic and produces it into a Kafka topic. Unlike the reverse direction, this relay is responsible for managing its own state because the booblik broker does not track consumer positions.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant B as Booblik Broker
    participant R as booblikToKafka Relay
    participant K as Kafka Broker
    participant S as FileOffsetStore

    R->>S: load(topic, partition)
    S-->>R: saved offset
    R->>B: follow(topic, StartPosition)
    loop Each Batch
        B-->>R: batch (records + nextOffset)
        R->>K: kafka.send(record)
        R->>K: kafka.flush()
        R->>S: save(topic, partition, nextOffset)
    end"
/>

## The `booblikToKafka` lifecycle [#the-boobliktokafka-lifecycle]

The lifecycle begins with the `BooblikSubscriber.follow` method, which initiates a stream from the booblik topic using a position retrieved from the `FileOffsetStore` (`Directions.kt:110-111`). The stream is processed via the `collect` operator, which receives batches of records. The `checkpointing` operator is applied to the flow, ensuring that the `OffsetStore` is updated only after the collector has successfully processed the batch (`Directions.kt:112`).

## The `FileOffsetStore` mechanism [#the-fileoffsetstore-mechanism]

Since the booblik broker does not remember readers, the relay maintains state using a `FileOffsetStore` on its own volume (`Directions.kt:93`). To ensure position integrity, the `save` method writes the offset to a temporary file and then performs an atomic move using `StandardCopyOption.ATOMIC_MOVE` (`FileOffsetStore.kt:57`). This prevents a partially written or truncated offset from being parsed during a restart (`FileOffsetStore.kt:25`).

## At-least-once delivery and `kafka.flush` [#at-least-once-delivery-and-kafkaflush]

The relay guarantees at-least-once delivery through a specific sequence of operations within the `collect` block. First, all records in a batch are sent to Kafka via `kafka.send` (`Directions.kt:117`). Second, `kafka.flush()` is called to ensure all buffered records are actually acknowledged by the Kafka broker (`Directions.kt:119`). Only after the flush is successful does the `checkpointing` mechanism save the `batch.nextOffset` to the `FileOffsetStore` (`Directions.kt:120`).

## The `mirrored` topic and partition mapping [#the-mirrored-topic-and-partition-mapping]

When moving data from booblik to Kafka, the relay faces a limitation: booblik's wire protocol does not store keys, only the `partitionId` (`README.md:177-179`). Consequently, when the relay calls `kafka.send(ProducerRecord(config.kafkaTopic, record))`, it does not provide a key (`Directions.kt:117`). This means the distribution of records into Kafka partitions is determined by Kafka's default partitioning logic rather than any specific key-based rule chosen by the original producer.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines    | What is there                                                            |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------ |
| [`…/relay/Directions.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt#L89-L124 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Directions.kt")                    | `89-124` | The `booblikToKafka` function implementation and its logic.              |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27-L61 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `27-61`  | The `FileOffsetStore` class and its atomic file-based persistence logic. |
| [`…/relay/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt#L47-L70 "dev/relay/src/main/kotlin/ru/workinprogress/booblik/dev/relay/Main.kt")                                       | `47-70`  | The `main` function containing the reconnection loop and error handling. |

## Behaviour that surprises [#behaviour-that-surprises]

* **At-least-once duplicates**: Because the `checkpointing` happens after `kafka.flush()`, a crash between these two calls will result in the same batch being re-sent to Kafka upon restart (`Directions.kt:119-120`).
* **Key loss**: While the `TopicHandle.send` in the `kafkaToBooblik` direction uses the Kafka key to pick a booblik partition, the `booblikToKafka` direction loses this key entirely because it is not part of the booblik wire protocol (`README.md:177-180`).
* **Silent corruption prevention**: The `FileOffsetStore` uses a `.tmp` file and an atomic move specifically to avoid the "half-saved" offset bug where a truncated file could lead to silent data replay (`FileOffsetStore.kt:24-25`).
