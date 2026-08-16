# booblik-benchmark (/wiki/booblik-benchmark)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblik-benchmark` module provides a comprehensive suite of performance measurements for the `booblik` project. It includes JMH-based benchmarks to measure throughput and latency of core components, as well as specialized "probes" designed to answer specific architectural questions regarding durability, recovery, and abstraction overhead.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Benchmarks (JMH)&#x22;
        B1[FetchDecodeBenchmark] -->|Measures| C1[Decoding Overhead]
        B2[FlowOverheadBenchmark] -->|Measures| C2[Flow Abstraction Cost]
        B3[PartitionWriterBenchmark] -->|Measures| C3[Batching & Ack Efficiency]
        B4[GroupCommitBenchmark] -->|Measures| C4[Durability Barrier Scaling]
    end
    subgraph &#x22;Probes (Manual Execution)&#x22;
        P1[probeDurability]
        P2[probeStartup]
        P3[probeSustainedWrite]
        P4[probeLoad]
        P5[probeSubscription]
    end
    subgraph &#x22;Infrastructure&#x22;
        M[MeasurementDir] -->|Ensures| FS[Physical Storage]
        B1 & B2 & B3 & B4 & P1 & P2 & P3 & P4 & P5 --> M
    end"
/>

## MeasurementDir and volatile filesystems [#measurementdir-and-volatile-filesystems]

To prevent misleading results where `fsync` operations return instantly because they are running on RAM-backed filesystems like `tmpfs`, the `MeasurementDir` object enforces that all measurements occur on real physical storage. As defined in [`MeasurementDir.kt:32-47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L32-L47), the system explicitly refuses to create a directory if the underlying `FileStore` type is identified as `tmpfs` or `ramfs`.

More: [MeasurementDir and volatile filesystems](booblik-benchmark/measurementdir-and-volatile)

## FetchDecodeBenchmark and the JVM reader cost [#fetchdecodebenchmark-and-the-jvm-reader-cost]

This benchmark addresses M-140 by comparing the performance of the legacy `ResponseReader` (which uses `ByteBuffer`) against the new `ResponseDecoder` (which uses `ByteArray` and index arithmetic). As detailed in [`FetchDecodeBenchmark.kt:37-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L37-L40), the goal is to determine if merging these two decoders imposes a measurable cost on the platform where reading occurs, with the test varying `recordSize` from 64 B to 8 KiB.

More: [FetchDecodeBenchmark and the JVM reader cost](booblik-benchmark/fetchdecodebenchmark-and-the)

## FlowOverheadBenchmark and the cost of abstraction [#flowoverheadbenchmark-and-the-cost-of-abstraction]

This benchmark isolates the cost of the Kotlin `Flow` machinery by removing the network component and comparing three distinct execution shapes: a standard `loop`, a `coldFlow`, and a `bufferedFlow`. According to [`FlowOverheadBenchmark.kt:24-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt#L24-L35), this allows the developer to distinguish between the inherent cost of the `Flow` abstraction and the decoupling cost introduced by a channel/buffer.

## PartitionWriterBenchmark and batching efficiency [#partitionwriterbenchmark-and-batching-efficiency]

This benchmark measures the overhead of the write actor and the efficiency gains provided by batching. As described in [`PartitionWriterBenchmark.kt:28-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L28-L43), it compares a single-record unit against various `batchSize` settings and `AckPolicy` configurations to determine the "actor's price" in terms of records per second.

## GroupCommitBenchmark and the durability barrier [#groupcommitbenchmark-and-the-durability-barrier]

This benchmark analyzes how throughput scales as the number of concurrent producers increases and how the `groupWindowMillis` affects the durability barrier. As noted in [`GroupCommitBenchmark.kt:34-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt#L34-L43), it tests the hypothesis that a writer collecting multiple producers into a single group can increase throughput without significantly increasing the latency of the individual producers.

## Benchmark configuration and task registration [#benchmark-configuration-and-task-registration]

The module uses a custom Gradle configuration to manage JMH targets and specialized probes. The `build.gradle.kts` file defines several benchmark configurations:

| Configuration | Description                                                         |
| ------------- | ------------------------------------------------------------------- |
| `main`        | Full run with 5 warmups and 10 iterations                           |
| `writer`      | Focuses on `PartitionWriterBenchmark`                               |
| `flow`        | Focuses on `FlowOverheadBenchmark`                                  |
| `decode`      | Focuses on `FetchDecodeBenchmark`                                   |
| `ci`          | Excludes `GroupCommitBenchmark` and uses `flushEveryAppend = false` |
| `quick`       | Short run for rapid feedback (2 warmups, 3 iterations)              |

The [`build.gradle.kts:132-140`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L132-L140) also registers several `probes` as `JavaExec` tasks, which are ordinary programs used to answer specific architectural questions rather than providing average throughput.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                        | Lines    | What is there                                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------- |
| [`…/benchmark/MeasurementDir.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L27-L49 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt")                               | `27-49`  | Logic for creating measurement directories and preventing volatile filesystems. |
| [`booblik-benchmark/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L57-L125 "booblik-benchmark/build.gradle.kts")                                                                                                                                   | `57-125` | JMH target registrations and benchmark configurations.                          |
| [`…/benchmark/FetchDecodeBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt#L37-L40 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FetchDecodeBenchmark.kt")             | `37-40`  | Benchmark class for comparing JVM reader and decoder.                           |
| [`…/benchmark/FlowOverheadBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt#L18-L35 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt")          | `18-35`  | Benchmark class for measuring Flow abstraction overhead.                        |
| [`…/benchmark/GroupCommitBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt#L29-L43 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt")             | `29-43`  | Benchmark class for measuring group commit scaling.                             |
| [`…/benchmark/PartitionWriterBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L28-L43 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt") | `28-43`  | Benchmark class for measuring batching and acknowledgement costs.               |

## Behaviour that surprising [#behaviour-that-surprising]

* The `MeasurementDir` object treats a volatile filesystem as a **refusal** rather than a warning, throwing an `IllegalStateException` to prevent users from believing "fast" but false results (`MeasurementDir.kt:37-47`).
* Benchmarks are placed in the `main` source set rather than a dedicated benchmark source set so that any refactoring that breaks a benchmark immediately breaks the build (`build.gradle.kts:54-56`).
* In `GroupCommitBenchmark`, the score is reported as **invocations per second**, meaning the actual records per second must be calculated by multiplying the score by the number of producers (`GroupCommitBenchmark.kt:41-43`).
