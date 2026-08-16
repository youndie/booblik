# Metadata Retrieval (/wiki/clients-dotnet-Booblik.Conformance/metadata-retrieval)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant B as Broker (Session)
    participant P as PartitionRegistry
    
    C->>B: MetadataRequest (topics)
    B->>P: describe()
    P-->>B: TopicMetadata (handles)
    B->>B: Map handles to PartitionMetadata
    B->>C: MetadataResponse (topics, partitions, offsets)"
/>

## MetadataRequest [#metadatarequest]

A `MetadataRequest` is a specialized request that does not target a specific partition but rather asks the broker for information about one or more topics [`Session.kt:94-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L94-L97). If the request contains no topics, the broker is expected to return metadata for all available topics [`Session.kt:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L124-L126).

## TopicMetadata and PartitionMetadata [#topicmetadata-and-partitionmetadata]

The broker responds with a hierarchy of metadata structures. A `TopicMetadata` object contains a list of `PartitionMetadata` for each partition in a topic [`Session.kt:136-149`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L136-L149).

The `PartitionMetadata` includes:

| Field            | Description                                                                                                                                                                                                                                        |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`             | The partition identifier                                                                                                                                                                                                                           |
| `logStartOffset` | The first offset currently available in the log after retention [`Session.kt:142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L142)     |
| `highWatermark`  | The first offset that does not exist yet (the next expected offset) [`Session.kt:146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L146) |

## UNKNOWN\_TOPIC\_OR\_PARTITION Error Handling [#unknown_topic_or_partition-error-handling]

The protocol enforces strict topic validation. If a request names a topic that the broker does not possess, the broker must fail the **entire** request with `UNKNOWN_TOPIC_OR_PARTITION` rather than simply omitting the missing topic from the response [`Session.kt:115-118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L115-L118). This distinction ensures that a client can differentiate between a topic that is empty and a topic that does not exist [`Session.kt:127-131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L127-L131).

## MetadataResponse Decoding [#metadataresponse-decoding]

The decoding process involves parsing a series of nested structures from the wire format [`wire.py:209-232`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L209-L232). The sequence is as follows:

1. **Topic Count**: An integer representing the number of topics [`wire.py:211`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L211).
2. **Topic Name**: A length-prefixed UTF-8 string [`wire.py:216-219`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L216-L219).
3. **Partition Count**: An integer representing the number of partitions in the topic [`wire.py:221`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L221).
4. **Partition Data**: A sequence of `PartitionInfo` containing the partition ID, log start offset, and high watermark [`wire.py:226`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L226).

## Metadata Conformance Testing [#metadata-conformance-testing]

The conformance harness verifies that the client correctly interprets the broker's response by checking the reported offsets [`Program.cs:107`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L107). Specifically, it validates that the `partition` ID, `logStartOffset`, and `highWatermark` are correctly extracted and printed to the standard output [`Program.cs:107`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L107).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                         | Lines     | What is there                                                          |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ---------------------------------------------------------------------- |
| [`…/net/Session.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L121-L152 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt") | `121-152` | The logic for handling and responding to `MetadataRequest`             |
| [`…/booblik/wire.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L209-L232 "clients/python/booblik/wire.py")                                                                            | `209-232` | The Python implementation of metadata response decoding                |
| [`…/harness/wire.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L109-L132 "conformance/harness/wire.py")                                                                                  | `109-132` | The reference implementation of metadata request encoding and decoding |

## Behaviour that surprises [#behaviour-that-surprises]

* The `highWatermark` reported in `PartitionMetadata` is read directly from the log's `nextOffset` rather than a published watermark to ensure it reflects what is actually readable [`Session.kt:144-146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L144-L146).
* In `MetadataRequest`, if a request names no topics, the broker returns metadata for everything [`Session.kt:124-125`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L124-L125).
* A `MetadataResponse` that is cut short by a broker restart is treated as a `ProtocolError` rather than a successful empty response [`wire.py:230-232`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L230-L232).
