# The performance floor (/wiki/ci/the-performance-floor)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Benchmark Run] --> B{Check Throughput}
    B -- Below Floor --> C[Fail CI: Collapse Detected]
    B -- Above Floor --> D[Pass CI: No Collapse]
    C --> E[Investigate Code/Hardware]
    D --> F[Report Artifacts Saved]"
/>

## The benchmark floor [#the-benchmark-floor]

The logic and purpose of the CI check is to provide a "floor" that is an order of magnitude below the slowest plausible runner to avoid false positives caused by hardware variance. As noted in [`benchmark-floor.sh:11-13`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/benchmark-floor.sh#L11-L13), the check deliberately ignores fine-grained regressions because different CI runs occur on different machines with different neighbors, making percentage-based comparisons unreliable. The script calculates a score by parsing the JMH report and comparing the whole number part of the throughput against a predefined threshold ([`benchmark-floor.sh:24-51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/benchmark-floor.sh#L24-L51)).

## SegmentMode and the disk barrier [#segmentmode-and-the-disk-barrier]

The benchmarks distinguish between different write paths, specifically focusing on how they interact with the disk barrier. The `mainCiBenchmark` task excludes certain rows to avoid measuring the "disk barrier" (the cost of `fsync` or `msync`), which is too variable on hosted runners to be meaningful ([`benchmark.yml:22-28`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L22-L28)). The two primary modes are:

| Mode           | Description                                                                                                                                                                                                                                                                                                     |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FILE_CHANNEL` | Uses `FileChannel` where the file length acts as a boundary for recovery ([`RecoveryTest.kt:67-71`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L67-L71)).                                  |
| `MAPPED`       | Uses memory mapping where the file is pre-sized, requiring a zero-length prefix to mark record boundaries ([`RecoveryTest.kt:87-91`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L87-L91)). |

## SparseOffsetIndex [#sparseoffsetindex]

The `SparseOffsetIndex` provides a memory-efficient way to map offsets to positions without the allocation overhead of a standard `Map`. It uses a `LongArray` where each entry stores a relative offset in the high 32 bits and a position in the low 32 bits ([`SparseOffsetIndex.kt:15-16`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L15-L16)).

The mechanics of the index include:

* **Sparsity**: An entry is only added when `intervalBytes` (default 4 KiB) of log data has passed ([`SparseOffsetIndex.kt:73-75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L73-L75)).
* **Lookup**: A binary search is performed on the `entries` array to find the largest entry where the relative offset is less than or equal to the target ([`SparseOffsetIndex.kt:124-136`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L124-L136)).
* **Concurrency**: The `count` is marked `@Volatile` and updated after the array slot is filled to ensure readers see a consistent view ([`SparseOffsetIndex.kt:80-82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L80-L82)).

## Recovery after a crash [#recovery-after-a-crash]

The system is designed to survive a `SIGKILL` during a write operation, ensuring the log remains usable. The invariant is that reopening the log must result in a log that ends on a record boundary with valid checksums ([`CrashRecoveryTest.kt:24-28`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/CrashRecoveryTest.kt#L24-L28)).

Recovery behavior depends on the `SegmentMode`:

* **FILE\_CHANNEL**: Recovery stops at the last intact record boundary because the file length bounds the data ([`RecoveryTest.kt:67-71`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L67-L71)).
* **MAPPED**: Since the file is pre-sized, recovery relies on the fact that the writer stores the body before the prefix; a crash leaves an "orphaned body" without a header, which is treated as the end of the log ([`RecoveryTest.kt:87-91`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L87-L91)).

## The write actor and group commit [#the-write-actor-and-group-commit]

The interaction between `append` calls and the `force` mechanism is central to performance. In a real scenario, group commits allow many appends to be collapsed into a single barrier. This can be tested using `CountingLog`, which tracks the number of `append` calls versus `force` calls ([`CountingLog.kt:10-12`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/CountingLog.kt#L10-L12)). By using `forceMillis` in the test, one can simulate a slow barrier to ensure that the grouping of multiple appends into a single `force` call is functioning as expected ([`CountingLog.kt:44`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/CountingLog.kt#L44)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                     | Lines     | What is there                                                       |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------- |
| [`ci/benchmark-floor.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/benchmark-floor.sh#L24-L25 "ci/benchmark-floor.sh")                                                                                                                                        | `24-25`   | Definition of the floor thresholds for `FILE_CHANNEL` and `MAPPED`. |
| [`…/storage/SparseOffsetIndex.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L130-L136 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt") | `130-136` | Binary search implementation for the sparse index lookup.           |
| [`…/log/CountingLog.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/CountingLog.kt#L37-L40 "booblik-core/src/test/kotlin/ru/workinprogress/booblik/log/CountingLog.kt")                                 | `37-40`   | The `append` implementation that increments the `appendCount`.      |

## Behaviour that surprises [#behaviour-that-surprises]

* The `SparseOffsetIndex` uses a `LongArray` instead of a `ConcurrentSkipListMap` specifically to avoid boxing `Offset` and `Position` values, which would cause allocations in the hot path ([`SparseOffsetIndex.kt:13-15`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L13-L15)).
* In `MAPPED` mode, a crash can leave "orphaned" bytes in the file that are not considered part of the log because they lack a valid header ([`RecoveryTest.kt:97-98`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/test/kotlin/ru/workinprogress/booblik/storage/RecoveryTest.kt#L97-L98)).
* The `count` in `SparseOffsetIndex` is updated *after* the entry is written to the array to ensure that a reader seeing a new `count` is guaranteed to see the corresponding data in the array ([`SparseOffsetIndex.kt:80-82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SparseOffsetIndex.kt#L80-L82)).
