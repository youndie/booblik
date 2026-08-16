# AckPolicy (/wiki/booblik-protocol/ackpolicy)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `AckPolicy` defines the durability contract between a producer and the broker. It determines how long a producer waits for an acknowledgement and what level of guarantee is provided regarding the persistence of the data. This policy is processed by the `PartitionWriter`, which manages the lifecycle of a write from the moment it enters the mailbox until it is either written to the log or flushed to disk.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Producer
    participant W as PartitionWriter
    participant L as Log (Storage)

    P->>W: append(records, policy)
    Note over W: Command enters mailbox
    
    loop runLoop()
        W->>W: Drain mailbox into group
        W->>L: writeBatch(records)
        
        alt policy == FORCED
            W->>L: force() (Disk Barrier)
        end
        
        W-->>P: complete(baseOffset)
    end"
/>

## AckPolicy modes [#ackpolicy-modes]

The durability promises are represented as a single byte on the wire and are defined in [`AckPolicy.kt:21-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/log/AckPolicy.kt#L21-L25):

| Mode      | Promise                                   | Durability Guarantee                                |
| --------- | ----------------------------------------- | --------------------------------------------------- |
| `NONE`    | Promises nothing.                         | Fire-and-forget; no reply is sent to the producer.  |
| `WRITTEN` | Bytes are in the log and offset is final. | No disk barrier; data may be lost on power failure. |
| `FORCED`  | Data is durable.                          | A disk barrier (`force`) is performed.              |

## The PartitionWriter write loop and group commit [#the-partitionwriter-write-loop-and-group-commit]

The `PartitionWriter` uses an actor-like model where a single coroutine owns the write side of a partition. The `runLoop` function in [`PartitionWriter.kt:163-196`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L163-L196) implements a group commit mechanism. Instead of processing one message at a time, it takes the first command and then drains all other currently queued messages from the mailbox without suspending. This allows the writer to aggregate multiple `WriteCommand` objects into a single group, ensuring that if any command in the group requires a durability guarantee, the cost of the disk barrier is shared.

## The distinction between WRITTEN and FORCED [#the-distinction-between-written-and-forced]

The distinction lies in the execution of the disk barrier. As seen in [`PartitionWriter.kt:178-184`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L178-L184), the writer iterates through the group and checks if any command's policy is `AckPolicy.FORCED`. If so, it triggers `flushNow()`, which calls `log.force()`. In contrast, `AckPolicy.WRITTEN` only ensures the bytes are appended to the log segment, and as verified in [`PartitionWriterTest.kt:96-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt#L96-L97), it must not trigger a `force` operation.

## AckPolicy.NONE and the fire-and-forget mechanism [#ackpolicynone-and-the-fire-and-forget-mechanism]

When `AckPolicy.NONE` is used, the `append` function in [`PartitionWriter.kt:135-149`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L135-L149) returns `null` immediately. This is because the offset is not known until the writer coroutine actually processes the batch in the `runLoop`. Because no `CompletableDeferred` is created for the acknowledgement, the producer receives no reply, making it a true fire-and-forget operation.

## Verification of offset continuity and batching [#verification-of-offset-continuity-and-batching]

The system ensures that offsets are assigned in a strict, gap-free sequence. Tests in [`PartitionWriterTest.kt:45-55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt#L45-L55) verify that a batch of records results in a single base offset and that subsequent records follow consecutively. Furthermore, tests in [`PartitionWriterTest.kt:129-155`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt#L129-L155) confirm that even with many concurrent producers, the resulting offsets are unique and form a continuous range from `0` to `N`.

## Testing group commit efficiency [#testing-group-commit-efficiency]

The efficiency of the group commit is validated by simulating high contention. In [`PartitionWriterTest.kt:105-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt#L105-L126), multiple producers send `FORCED` requests simultaneously. The test asserts that the number of actual `force` calls (disk barriers) is significantly lower than the number of producers, proving that the `PartitionWriter` successfully collapses multiple requests into a single barrier.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                               | Lines     | What is there                                                     |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------------------- |
| [`…/log/AckPolicy.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/log/AckPolicy.kt#L21-L25 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/log/AckPolicy.kt")             | `21-25`   | The `AckPolicy` enum defining the three durability levels.        |
| [`…/log/PartitionWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L163-L196 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt")             | `163-196` | The `runLoop` implementation containing the group commit logic.   |
| [`…/log/PartitionWriterTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt#L105-L126 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/PartitionWriterTest.kt") | `105-126` | Tests verifying that group commit amortizes the cost of barriers. |
| [`…/java/AckPolicy.java`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/src/main/java/ru/workinprogress/booblik/java/AckPolicy.java#L4-L20 "clients/java/src/main/java/ru/workinprogress/booblik/java/AckPolicy.java")                             | `4-20`    | The Java implementation of the `AckPolicy` enum.                  |

## Behaviour that surprising [#behaviour-that-surprising]

* `PartitionWriter.append` returns `null` for `AckPolicy.NONE` because the offset is not determined until the command is processed by the actor, as noted in [`PartitionWriter.kt:130-133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L130-L133).
* The `highWatermark` is updated only once per group of writes, rather than per record, to avoid unnecessary writes on the hot path ([`PartitionWriter.kt:92-95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L92-L95)).
* A `FORCED` policy in a group will trigger a single `log.force()` for all members of that group, meaning the latency of the barrier is shared across all producers in that batch ([`PartitionWriter.kt:37-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/log/PartitionWriter.kt#L37-L43)).
