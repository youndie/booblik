# dev/common (/wiki/dev-common)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-16, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[OffsetStore Interface] --> B[FileOffsetStore]
    B --> C[Local Filesystem]
    C --> D[Temporary File]
    D --> E[Atomic Move to Target File]"
/>

## FileOffsetStore [#fileoffsetstore]

The primary implementation of the OffsetStore interface using the local filesystem, as seen in [`FileOffsetStore.kt:27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27).

More: [FileOffsetStore](dev-common/fileoffsetstore)

## Atomic offset persistence [#atomic-offset-persistence]

The mechanism of writing offsets via temporary files and atomic moves to prevent data corruption, implemented in [`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57).

More: [Atomic offset persistence](dev-common/atomic-offset-persistence)

## load and save operations [#load-and-save-operations]

The lifecycle of reading and writing offsets for a specific TopicName and PartitionId, handled by the `load` and `save` methods in [`FileOffsetStore.kt:34-58`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L34-L58).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------------- |
| [`…/common/FileOffsetStore.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27-L64 "dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt") | `27-64` | The implementation of the `FileOffsetStore` class and its file-based logic. |

## Public API [#public-api]

| What              | Where                                                                                                                                                                                              | Why                                                                        |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `FileOffsetStore` | [`FileOffsetStore.kt:27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L27) | Provides a filesystem-based implementation of the `OffsetStore` interface. |

## Behaviour that surprise [#behaviour-that-surprise]

* `save` uses a temporary file and an atomic move ([`FileOffsetStore.kt:55-57`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L55-L57)) to ensure that a partially written offset does not corrupt the state.
* `load` returns `null` if the offset file does not exist on the disk ([`FileOffsetStore.kt:40`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/common/src/main/kotlin/ru/workinprogress/booblik/dev/common/FileOffsetStore.kt#L40)).
