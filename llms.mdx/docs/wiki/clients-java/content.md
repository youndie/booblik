# clients/java (/wiki/clients-java)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

Documentation for the booblik Java client module.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Connection
    participant T as Topic
    participant P as Producer
    participant K as Consumer
    
    C->>T: topic(name)
    T->>P: send(payload, key, ack)
    P-->>P: Accumulate/Batch
    P->>C: ProduceRequest
    C-->>P: ProduceResult
    
    C->>K: consumer(topic, partition, offset)
    K->>C: fetch(maxBytes)
    C-->>K: Iterable<byte[]>"
/>

## Connection and Topic Management [#connection-and-topic-management]

The lifecycle of a `Connection` is managed via a try-with-resources block, as seen in the usage examples in [`README.md:9-12`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L9-L12). Topics are accessed through the connection via the `topic(String)` method, which retrieves partition information directly from the broker as described in [`README.md:10`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L10).

More: [Connection and Topic Management](clients-java/connection-and-topic)

## Producer Configuration and Batching [#producer-configuration-and-batching]

Performance is heavily dependent on the accumulator, where batching significantly increases throughput compared to sending single records ([`README.md:36-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L36-L37)). Users can tune the accumulator using `ProducerConfig`, which is defined in [`ProducerConfig.java:4`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L4).

The `ProducerConfig` parameters are:

| Parameter      | Type        | Description                         |
| -------------- | ----------- | ----------------------------------- |
| `maxBatchSize` | `int`       | The maximum size of a batch         |
| `lingerMillis` | `long`      | Time to wait before sending a batch |
| `ack`          | `AckPolicy` | The acknowledgement policy          |

The default configuration is provided by `ProducerConfig.defaults()` in [`ProducerConfig.java:15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L15).

More: [Producer Configuration and Batching](clients-java/producer-configuration-and)

## ProduceResult and AckPolicy [#produceresult-and-ackpolicy]

A produce operation returns a `ProduceResult`, which contains the `baseOffset` and `logEndOffset` of the written batch ([`ProduceResult.java:4`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProduceResult.java#L4)).

The `AckPolicy` determines the acknowledgement behavior:

| Mode      | Behavior                                                                                                                                                                                                             |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WRITTEN` | Standard acknowledgement                                                                                                                                                                                             |
| `NONE`    | Returns `null` or `Producer.OFFSET_UNKNOWN`; broker may drop records silently ([`README.md:59-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L59-L64)) |

## PartitionInfo and Partitioning [#partitioninfo-and-partitioning]

Metadata for a partition is encapsulated in the `PartitionInfo` record, which includes the `partition` index, the `logStartOffset` (the start of the live log after retention), and the `highWatermark` ([`PartitionInfo.java:7-9`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/PartitionInfo.java#L7-L9)).

Partitioning logic is influenced by the key; while `topic.send` uses a key, the client's internal logic ensures that the key itself is not sent to the broker, but rather the partition index is determined by the key ([`README.md:65-67`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L65-L67)).

## Consumer Iteration and Position Management [#consumer-iteration-and-position-management]

The `Consumer` implements `Iterable<byte[]>`, allowing for a standard for-each loop ([`README.md:90-92`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L90-L92)). The loop is blocking: `hasNext()` will block until data is available or the socket times out ([`README.md:101`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L101)).

To ensure "at-least-once" delivery, the caller must manually track the `position()` of the consumer and persist it *after* the records are processed ([`README.md:93-94`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L93-L94)).

## RecordExceedsMaxBytesException [#recordexceedsmaxbytesexception]

If a record is larger than the client's configured `maxBytes` limit, a `RecordExceedsMaxBytesException` is thrown ([`README.md:115-116`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L115-L116)). This is a terminal error that cannot be resolved by retrying, as the record will never fit in the buffer ([`RecordExceedsMaxBytesException.java:10-13`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/RecordExceedsMaxBytesException.java#L10-L13)).

The exception contains the following fields:

| Field         | Type   | Description                          |
| ------------- | ------ | ------------------------------------ |
| `offset`      | `long` | The offset of the problematic record |
| `recordBytes` | `int`  | The size of the record in bytes      |
| `maxBytes`    | `int`  | The maximum allowed bytes            |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                               | Lines  | What is there                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ----------------------------------------------------- |
| [`…/java/ProduceResult.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProduceResult.java#L4 "clients/java/src/main/java/ru/workinprogress/booblik/java/ProduceResult.java")                                                     | `4`    | The `ProduceResult` record definition                 |
| [`…/java/PartitionInfo.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/PartitionInfo.java#L7-L9 "clients/java/src/main/java/ru/workinprogress/booblik/java/PartitionInfo.java")                                                  | `7-9`  | Documentation for `PartitionInfo` fields              |
| [`…/java/ProducerConfig.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java#L4 "clients/java/src/main/java/ru/workinprogress/booblik/java/ProducerConfig.java")                                                  | `4`    | The `ProducerConfig` record definition                |
| [`…/java/RecordExceedsMaxBytesException.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/RecordExceedsMaxBytesException.java#L15 "clients/java/src/main/java/ru/workinprogress/booblik/java/RecordExceedsMaxBytesException.java") | `15`   | The `RecordExceedsMaxBytesException` class definition |
| [`…/java/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L9-L12 "clients/java/README.md")                                                                                                                                                                      | `9-12` | Example of connection and topic usage                 |
| [`…/java/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/build.gradle.kts#L34 "clients/java/build.gradle.kts")                                                                                                                                                    | `34`   | Compilation release version configuration             |

## Behaviour that surprise [#behaviour-that-surprise]

* `ProducerConfig.defaults()`: A `linger` of zero is not the fastest setting; it sends every record individually, which is significantly slower than batching ([`README.md:10-12`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L10-L12)).
* `AckPolicy.NONE`: This mode is the only one where the broker may drop an accepted record silently ([`README.md:63-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L63-L64)).
* `Consumer`: The `hasNext()` method is always true and blocks until data is available because a partition has no defined end ([`README.md:101-102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L101-L102)).
