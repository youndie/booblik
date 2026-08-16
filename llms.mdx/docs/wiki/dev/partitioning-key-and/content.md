# Partitioning by key and consumer-side position (/wiki/dev/partitioning-key-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Publisher (Keyed)
    participant B as Broker (Partitions)
    participant C as Consumer (OffsetStore)
    
    P->>P: Hash(Key) -> Partition ID
    P->>B: Send Event (Partition ID)
    B->>B: Append to Log
    C->>B: Fetch Batch (Partition ID)
    C->>C: Process Batch
    C->>C: Save Offset (FileOffsetStore)"
/>

## The Keyed Partitioner [#the-keyed-partitioner]

The client uses a key to determine which partition a record belongs to before the record is sent. As described in [`README.md:19-21`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L19-L21), the `TopicHandle` hashes the key and sends the broker a partition number; the key itself never reaches the wire. This ensures that all events for a specific user are routed to the same partition, allowing a single service to handle them in order.

## FileOffsetStore [#fileoffsetstore]

To ensure that a consumer's position survives a process restart, the `FileOffsetStore` implements `OffsetStore` by writing the position to a file on a volume. As detailed in [`README.md:30-32`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L30-L32), this is achieved through a temporary file and an atomic move to prevent corruption. This mechanism ensures that a "half-saved" position is not written, which would otherwise cause silent replays of truncated offsets.

## At-least-once Checkpointing [#at-least-once-checkpointing]

The system guarantees at-least-once delivery by managing the lifecycle of a batch carefully. According to [`README.md:35-37`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L35-L37), `checkpointing` saves the offset only **after** the collector has successfully handled the batch. If a failure occurs between the handling of the batch and the saving of the offset, the batch will be replayed upon restart.

## Recovery after a crash [#recovery-after-a-crash]

When a consumer restarts, it uses its persisted position to resume reading. The `resumedFrom` field in the consumer's `/stats` endpoint provides visibility into this process ([`README.md:52-53`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L52-L53)). As noted in [`check.py:14-16`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L14-L16), resuming slightly **behind** the last known position is considered legal behavior because the at-least-once guarantee allows for the replay of a batch that was handled but not yet checkpointed.

## The split assertion [#the-split-assertion]

The integrity of the partitioning and consumption is validated by an external check. The `check.sh` script triggers a validation where `check.py` asserts that every record sent by the publisher reaches exactly one consumer ([`README.md:44-47`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L44-L47)). The `split` mode in [`check.py:25-38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L25-L38) verifies that the number of records written to a partition matches the position held by the consumer assigned to that partition.

## Key files [#key-files]

| File                                                                                                                                      | Lines   | What is there                                                 |
| ----------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------- |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L19-L22 "dev/README.md") | `19-22` | Mechanics of keyed partitioning and partition assignment.     |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L30-L33 "dev/README.md") | `30-33` | Implementation details of `FileOffsetStore` and atomic moves. |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L35-L38 "dev/README.md") | `35-38` | Explanation of at-least-once checkpointing logic.             |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L52-L53 "dev/README.md") | `52-53` | Behavior of the `resumedFrom` field during restarts.          |
| [`dev/check.py`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.py#L25-L38 "dev/check.py")    | `25-38` | Logic for asserting partition/position matches.               |
| [`dev/check.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check.sh#L40-L48 "dev/check.sh")    | `40-48` | Orchestration of the split assertion check.                   |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Replays**: Because checkpointing happens after processing, a consumer might start from a position slightly behind its last successful work, causing it to replay the last batch ([`README.md:36-37`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L36-L37)).
* **Key Erasure**: A Kafka key does not survive the crossing into booblik; while it is used to pick the partition on the way in, it is not stored in the booblik wire format and cannot be recovered on the way back ([`README.md:177-180`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L177-L180)).
* **Non-monotonicity in `partitionFor(null)`**: Calling `partitionFor(null)` advances an internal counter, meaning that checking which partition a record *would* go to before actually sending it can cause records to skip partitions ([`README.md:204-206`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L204-L206)).
