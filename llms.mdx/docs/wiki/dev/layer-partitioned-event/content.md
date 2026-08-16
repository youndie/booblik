# Layer 1: Partitioned event stream with consumer-side position (/wiki/dev/layer-partitioned-event)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Publisher (Client)
    participant B as Booblik Broker
    participant C as Consumer (Service)
    participant S as FileOffsetStore (Volume)

    P->>P: Hash Key -> Partition ID
    P->>B: Produce(Partition, Payload)
    B-->>P: Ack (Offset)
    
    C->>B: Fetch(Partition, StartOffset)
    B-->>C: Batch (Records)
    C->>C: handle(record)
    C->>S: save(offset) via Atomic Move
    C->>B: Commit (Implicit via position)"
/>

## The Key-to-Partition Mapping [#the-key-to-partition-mapping]

The client is responsible for the routing logic. Every event is associated with a key (such as a user ID), and the `TopicHandle.partitionFor` function hashes this key to determine a specific partition number ([`Main.kt:79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L79)). Crucially, the key itself is never sent over the wire; only the resulting partition number is transmitted in the protocol ([`README.md:20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L20)). This ensures that all events for a single user land in the same partition, guaranteeing that a single consumer handles them in the correct order ([`README.md:21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L21)).

## FileOffsetStore and Atomic Position Persistence [#fileoffsetstore-and-atomic-position-persistence]

To ensure that a consumer's position survives a crash, the `FileOffsetStore` implements persistence using a file on a dedicated volume ([`FileOffsetStore.kt:28`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L28)). To prevent data corruption during a crash, the `save` operation does not overwrite the existing file directly. Instead, it writes the new offset to a temporary file and then performs an atomic move using `StandardCopyOption.ATOMIC_MOVE` ([`FileOffsetStore.kt:57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L57)). This prevents a "half-saved" position, which would be parsed as a truncated number and cause silent replay of multiple records ([`FileOffsetStore.kt:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L24)).

## At-least-once Guarantee and Checkpointing [#at-least-once-guarantee-and-checkpointing]

The system provides an at-least-once delivery guarantee through the specific ordering of the `checkpointing` operation. The consumer handles the batch of records first and only saves the offset to the `OffsetStore` **after** the collector has successfully processed the batch ([`Main.kt:83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L83)). If a crash occurs between the handling of the records and the saving of the offset, the consumer will replay the batch upon restart ([`README.md:36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L36)).

## Recovery and the resumedFrom Field [#recovery-and-the-resumedfrom-field]

When a consumer restarts, it attempts to resume from its last known position. It uses `StartPosition.At(offset)` if a saved offset exists, otherwise it defaults to `StartPosition.Earliest` to read from the beginning of the live log ([`Main.kt:68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L68)). The `Stats` class tracks this via the `resumedFrom` field, which is populated with the saved offset if a restart occurs, allowing for visibility into whether the consumer is starting from a historical point or from the beginning ([`Main.kt:69`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L69)).

## Validation via check.sh [#validation-via-checksh]

The correctness of the consumer's recovery logic is verified by `check.sh`. The test suite considers it legal for a consumer to resume slightly behind its last known position, as this is a natural consequence of the at-least-once guarantee ([`README.md:37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L37)). However, the test will fail if the consumer attempts to start from the very beginning of the log when it should have resumed from a specific offset ([`README.md:38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L38)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------------------------------- |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L53-L57 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `53-57` | Implementation of atomic position saving via temporary files and moves.          |
| [`…/consumer/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L77-L88 "dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt")                        | `77-88` | The main consumer loop including `follow`, `checkpointing`, and record handling. |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L30-L33 "dev/README.md")                                                                                                                                                         | `30-33` | Documentation on `FileOffsetStore` and the risks of truncated offsets.           |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Replay:** Because of the way `FileOffsetStore` handles file writes, a truncated file (e.g., `"12"` becoming `"1"`) is still a valid long, which causes the consumer to silently replay eleven records ([`FileOffsetStore.kt:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L24)).
* **Key Erasure:** While the client uses a key to select a partition, the key itself is not part of the booblik wire protocol and is not stored or transmitted ([`README.md:20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L20)).
* **Earliest vs. Zero:** Using `StartPosition.Earliest` does not necessarily mean starting from offset zero; it means starting from the beginning of the *live* log, which depends on the current retention settings ([`Main.kt:65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L65)).
