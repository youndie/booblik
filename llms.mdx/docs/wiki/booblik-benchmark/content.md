# booblik-benchmark (/wiki/booblik-benchmark)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblik-benchmark` module provides the infrastructure for measuring the performance and characteristics of the `booblik` system. It includes mechanisms to ensure measurement validity by enforcing physical storage usage and specific JVM configurations, a suite of JMH-based benchmarks for throughput and overhead analysis, and specialized "probes" for answering specific architectural questions.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph Infrastructure
        MD[MeasurementDir: Physical Storage Check]
        RF[RuntimeFootprint: JVM Config Verification]
    end
    subgraph JMH_Benchmarks
        WB[PartitionWriterBenchmark: Write Actor Cost]
        GCB[GroupCommitBenchmark: Producer Scaling]
        FOB[FlowOverheadBenchmark: Flow Abstraction Cost]
        DB[DecodeBenchmark: Decoding Overhead]
    end
    subgraph Probes
        P[Probes: Standalone Shape Analysis]
    end
    MD --> WB
    RF --> WB
    WB --> GCB
    FOB --> P"
/>

## MeasurementDir [#measurementdir]

The mechanism for ensuring measurements are performed on physical storage rather than volatile filesystems like tmpfs. The `create` function in [`MeasurementDir.kt:39-49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L39-L49) refuses to return a directory if it resides on a `tmpfs` or `ramfs` filesystem, preventing results that describe memory instead of disk.

More: [MeasurementDir](booblik-benchmark/measurementdir)

## RuntimeFootprint [#runtimefootprint]

The verification mechanism that ensures the JVM is running with the specific memory and GC settings required for valid results. As implemented in [`RuntimeFootprint.kt:24-46`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt#L24-L46), the module checks that required flags like `-Xmx64M` and `-XX:+UseSerialGC` are present in the runtime arguments.

## BenchmarkConfiguration [#benchmarkconfiguration]

The definition of different benchmark targets including `main`, `writer`, `flow`, `decode`, `ci`, and `quick`, as well as the `common` throughput settings. The configuration is defined in [`build.gradle.kts:57-125`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L57-L125) and uses a `common` function to set `mode = "thrpt"` and `outputTimeUnit = "s"` as seen in [`build.gradle.kts:157-162`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L157-L162).

## FlowOverheadBenchmark [#flowoverheadbenchmark]

A benchmark measuring the cost of the `Flow` abstraction by comparing `loop`, `coldFlow`, and `bufferedFlow` with varying `batchSize`. This benchmark is implemented in [`FlowOverheadBenchmark.kt:40-86`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt#L40-L86) and uses a `@Param` for `batchSize` to observe how the cost per emission changes.

## PartitionWriterBenchmark [#partitionwriterbenchmark]

Measuring the cost of the write actor and batching by varying `batchSize`, `mode`, and `ackPolicy`. The benchmark in [`PartitionWriterBenchmark.kt:45-90`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L45-L90) uses `@OperationsPerInvocation(RECORDS_PER_OP)` to ensure the score is reported in records per second.

## GroupCommitBenchmark [#groupcommitbenchmark]

Evaluating the throughput of durable writes as the number of `producers` increases, considering `groupWindowMillis` and `mode`. This is handled in [`GroupCommitBenchmark.kt:45-99`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt#L45-L99), where the score is measured in invocations per second.

## Probes [#probes]

A collection of standalone `JavaExec` tasks designed to answer specific questions about system behavior (e.g., `probeDurability`, `probeStartup`). These are registered in [`build.gradle.kts:132-154`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L132-L154) and map task names to specific probe classes.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                        | Lines     | What is there                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------ |
| [`…/benchmark/MeasurementDir.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L32 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt")                                   | `32`      | The `VOLATILE` set containing `tmpfs` and `ramfs`.           |
| [`booblik-benchmark/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L157-L162 "booblik-benchmark/build.gradle.kts")                                                                                                                                  | `157-162` | The `common` configuration function for benchmarks.          |
| [`…/benchmark/FlowOverheadBenchmark.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt#L44-L45 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/FlowOverheadBenchmark.kt")          | `44-45`   | The `batchSize` parameter definition.                        |
| [`…/benchmark/GroupCommitBenchmark.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt#L49-L61 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/GroupCommitBenchmark.kt")             | `49-61`   | Parameters for `producers`, `groupWindowMillis`, and `mode`. |
| [`…/benchmark/PartitionWriterBenchmark.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L48-L55 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt") | `48-55`   | Parameters for `batchSize`, `mode`, and `ackPolicy`.         |
| [`…/benchmark/RuntimeFootprint.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt#L24 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt")                             | `24`      | The `REQUIRED` list of JVM flags.                            |

## Behaviour that surprising [#behaviour-that-surprising]

* The `MeasurementDir.create` function will throw an `IllegalStateException` if the directory is on a volatile filesystem, as seen in [`MeasurementDir.kt:43-47`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L43-L47).
* In `GroupCommitBenchmark.kt:42-43`, the score is reported as **invocations per second**, meaning the user must multiply by the number of producers to get records per second.
* The `PartitionWriterBenchmark.kt:98-101` includes `log.retainAtMost` inside the measured `append` method to ensure retention costs are included in the benchmark.
