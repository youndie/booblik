# The `Topic` and Partitioning Logic (/wiki/clients/the-topic-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Client[Client/Producer] -->|Request Metadata| Broker[Booblik Server]
    Client -->|Send Record| Topic[Topic]
    Topic -->|PartitionFor| Partitioner{Partitioner}
    Partitioner -->|Round-Robin| RP[Round-Robin Counter]
    Partitioner -->|Hash| H[Fnv1a Hash]
    RP -->|Select| PID[PartitionId]
    H -->|Select| PID
    PID -->|Produce| Log[Partition Log]"
/>

## Topic [#topic]

The abstraction of a named stream of records and its relationship to the broker's metadata. A `Topic` represents a logical grouping of partitions, and its structure is determined by the broker's current state. In the Kotlin client, a `TopicHandle` is obtained from a `Producer` and contains the `TopicName` and a list of available `PartitionId`s ([`Publishing.kt:19-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L19-L22)).

## PartitionFor [#partitionfor]

The mechanics of mapping a record key to a specific `PartitionId` using round-robin or hashing. The selection logic depends on whether a key is provided:

* **Unkeyed records**: Uses a round-robin strategy where a counter is incremented to ensure an even spread across partitions ([`Publishing.kt:40-42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L40-L42)).
* **Keyed records**: Uses a `Partitioner` (defaulting to `Fnv1a`) to map the key to a partition (`booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt:23, 44`).

## Metadata [#metadata]

The protocol for discovering existing topics and the error behavior when requesting unknown topics. The client can request metadata for all topics or a specific subset ([`BooblikClient.kt:70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L70)). If a requested topic does not exist on the broker, the request fails with an `UNKNOWN_TOPIC_OR_PARTITION` error rather than simply omitting the topic from the response ([`BooblikServer.kt:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L75-L77)).

## PartitionRegistry [#partitionregistry]

The server-side management of topic-partition mappings and the ordering of responses. The `PartitionRegistry` holds a map of `Key` (topic and partition) to `PartitionHandle` ([`BooblikServer.kt:79-81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L79-L81)). When describing all partitions, the registry ensures the output is ordered by topic name and then by partition ID to ensure deterministic response ordering ([`BooblikServer.kt:94-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L94-L97)).

## MetadataTest [#metadatatest]

Verification of topic existence, partition counts, and the distinction between empty and non-existent topics. The tests verify that:

* An empty metadata request describes every topic the broker has ([`MetadataTest.kt:30-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/MetadataTest.kt#L30-L37)).
* Naming specific topics narrows the response to only those topics ([`MetadataTest.kt:50-55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/MetadataTest.kt#L50-L55)).
* An unknown topic results in an error rather than an empty list, preventing ambiguity between a non-existent topic and an empty one ([`MetadataTest.kt:58-63`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/MetadataTest.kt#L58-L63)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                       | Lines    | What is there                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------- | ----------------------------------------------- |
| [`…/client/Publishing.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L19-L46 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt") | `19-46`  | `TopicHandle` class and its partitioning logic  |
| [`…/net/BooblikServer.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L79-L107 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt")              | `79-107` | `PartitionRegistry` and its key/ordering logic  |
| [`…/net/MetadataTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/MetadataTest.kt#L28-L77 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/MetadataTest.kt")                  | `28-77`  | Tests for metadata discovery and error handling |

## Behaviour that surprise [#behaviour-that-surprise]

* **Round-robin vs Random**: In `TopicHandle.partitionFor`, round-robin is used instead of random for unkeyed records because random distribution can visibly clump at small counts, which might be mistaken for a bug ([`Publishing.kt:31-33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L31-L33)).
* **Error vs Omission**: Requesting a non-existent topic via `metadata` returns an error code rather than an empty list to distinguish "no such topic" from "topic exists but is empty" ([`BooblikServer.kt:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L75-L77)).
* **Deterministic Ordering**: The `PartitionRegistry.describe` function explicitly sorts entries by topic and partition to ensure that identical requests result in identical response byte sequences ([`BooblikServer.kt:94-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/BooblikServer.kt#L94-L97)).
