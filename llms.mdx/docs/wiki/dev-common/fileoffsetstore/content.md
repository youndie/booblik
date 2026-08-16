# FileOffsetStore (/wiki/dev-common/fileoffsetstore)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `FileOffsetStore` provides a simple implementation of the `OffsetStore` interface, designed for consumers that need to persist their position in a log on a local volume. It ensures that a consumer's position outlives the process by storing the `Offset` in a file, allowing for recovery after a restart [`FileOffsetStore.kt:27-29`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27-L29).

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer
    participant F as File (Topic-Partition.offset)
    participant T as Temporary File (.tmp)

    Note over C, T: save(offset)
    C->>T: Write new offset string
    T->>F: Atomic Move (REPLACE_EXISTING)
    Note over C, T: load()
    F->>C: Read string & parse to Offset"
/>

## FileOffsetStore [#fileoffsetstore]

The `FileOffsetStore` is a concrete implementation of `OffsetStore` that uses the local file system to track progress. It maps a combination of `TopicName` and `PartitionId` to a specific file path within a configured directory [`FileOffsetStore.kt:61-64`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L61-L64).

## Atomic move via temporary files [#atomic-move-via-temporary-files]

To prevent data corruption, the `save` operation does not overwrite the existing offset file directly. Instead, it writes the new offset to a sibling file with a `.tmp` extension and then performs an atomic move using `StandardCopyOption.ATOMIC_MOVE` [`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57). This ensures that a partially written file (e.g., a truncated `"12"` becoming `"1"`) does not result in a valid but incorrect offset that could cause silent record replays [`FileOffsetStore.kt:23-25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L23-L25).

## At-least-once checkpointing [#at-least-once-checkpointing]

The system guarantees at-least-once delivery by performing `checkpointing` only **after** the collector has successfully handled a batch of records [`README.md:35-36`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L35-L36). If a failure occurs between the processing of the batch and the call to `save`, the consumer will restart from the previous offset and replay the batch [`README.md:36`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L36).

## File-based position recovery [#file-based-position-recovery]

When a consumer restarts, it uses the `load` function to retrieve the last known `Offset` from the file system [`FileOffsetStore.kt:34-46`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L34-L46). Because the broker does not store consumer positions, the responsibility for maintaining this state lies entirely with the client/consumer [`feature-subscribe-and-publish.md:30-31`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/features/feature-subscribe-and-publish.md#L30-L31).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                                           |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------------------- |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27-L64 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `27-64` | Implementation of `FileOffsetStore` including `load`, `save`, and file path resolution. |

## Behaviour that surprises [#behaviour-that-surprises]

* The `save` method uses a temporary file and `StandardCopyOption.ATOMIC_MOVE` to ensure that a position saved halfway through a write does not result in a truncated offset [`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57).
* `checkpointing` is designed for at-least-once semantics; saving the offset before handling the batch would result in at-most-once delivery, potentially skipping records [`README.md:35-36`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L35-L36).
* The `load` function returns `null` if the offset file does not exist, which is treated as a valid starting point for a new consumer [`FileOffsetStore.kt:40`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L40).
