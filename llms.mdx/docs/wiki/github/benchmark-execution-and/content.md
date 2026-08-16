# Benchmark execution and reporting (/wiki/github/benchmark-execution-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[GitHub Schedule/Dispatch] --> B[.github/workflows/benchmark.yml:34-36]
    B --> C[Gradle JMH Execution]
    C --> D{Benchmark Mode}
    D -->|mainCiBenchmark| E[Exclude Disk-Bound Tasks]
    D -->|Other| F[Full Suite]
    E --> G[benchmark.txt]
    G --> H[ci/benchmark-floor.sh:24-26]
    H --> I{Collapse Detected?}
    I -->|Yes| J[Fail Workflow]
    I -->|No| K[Upload Artifacts]
    K --> L[.github/workflows/benchmark.yml:79-88]"
/>

## The `mainCiBenchmark` mode [#the-maincibenchmark-mode]

The CI environment uses a specific configuration designed to filter out noise inherent to shared, hosted runners. Instead of running the full suite, it executes `mainCiBenchmark` as defined in [`build.gradle.kts:97-110`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L97-L110), which focuses on tasks that are not heavily influenced by the non-deterministic performance of a hosted runner's disk.

## The `benchmark-floor.sh` mechanism [#the-benchmark-floorsh-mechanism]

To avoid the pitfalls of comparing runs from different machines—where a 20-40% swing is common—the system does not look for percentage-based regressions. Instead, [`benchmark-floor.sh:24-26`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L24-L26) implements a "floor" mechanism that only triggers if performance collapses by an order of magnitude. It compares the current throughput against a hardcoded threshold (e.g., 20,000 for `FILE_CHANNEL` and 1,000,000 for `MAPPED`) to ensure that only catastrophic failures, rather than minor runner variance, cause a build failure.

## The `PartitionWriterBenchmark` lifecycle [#the-partitionwriterbenchmark-lifecycle]

The benchmark lifecycle is designed to measure the real-world cost of a broker's operations. In [`PartitionWriterBenchmark.kt:98-102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L98-L102), the `log.retainAtMost` call is explicitly included within the measured `@Benchmark` loop. This ensures that the overhead of log retention (cleaning up old segments) is accounted for in the throughput numbers, reflecting the actual performance a user would experience in a production environment.

## The `StartupProbe` and recovery scan [#the-startupprobe-and-recovery-scan]

The `StartupProbe` measures the efficiency of the system's recovery mechanism. As detailed in [`StartupProbe.kt:41-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/StartupProbe.kt#L41-L49), the probe measures the time taken to scan segments and rebuild the index. It performs three separate attempts to capture the difference between a "cold" restart (paying for the initial page cache miss) and "warm" restarts, providing a realistic range for how long a broker takes to recover after a crash.

## The `mainCiBenchmark` exclusion rules [#the-maincibenchmark-exclusion-rules]

To maintain a stable CI signal, certain benchmarks are explicitly excluded from the `ci` configuration in [`build.gradle.kts:111-117`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L111-L117). Specifically, `GroupCommitBenchmark` is excluded because its performance is heavily dependent on disk barriers and `fsync` latency, which vary wildly on hosted runners. Additionally, the `flushEveryAppend` parameter is set to `false` to avoid measuring paths that are primarily bounded by disk I/O latency rather than code efficiency.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                                         | Lines     | What is there                                                 |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------- |
| [`…/workflows/benchmark.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L33-L38 ".github/workflows/benchmark.yml")                                                                                                                                                    | `33-38`   | Workflow trigger configuration for scheduled and manual runs  |
| [`…/workflows/benchmark.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L69-L70 ".github/workflows/benchmark.yml")                                                                                                                                                    | `69-70`   | Execution of the `mainCiBenchmark` task                       |
| [`booblik-benchmark/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L111-L117 "booblik-benchmark/build.gradle.kts")                                                                                                                                   | `111-117` | Definition of the `ci` benchmark configuration and exclusions |
| [`ci/benchmark-floor.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L24-L26 "ci/benchmark-floor.sh")                                                                                                                                                                            | `24-26`   | Hardcoded performance floor values for different modes        |
| [`…/benchmark/PartitionWriterBenchmark.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt#L98-L102 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/PartitionWriterBenchmark.kt") | `98-102`  | Inclusion of retention logic in the benchmark loop            |
| [`…/probe/StartupProbe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/StartupProbe.kt#L41-L49 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/StartupProbe.kt")                              | `41-49`   | Logic for measuring recovery scan time and throughput         |

## Behaviour that surprises [#behaviour-that-surprises]

* **Non-comparable numbers:** Because of runner variance, the system explicitly refuses to compare the current CI run to the previous one; it only checks if the current run is "not a total collapse" via [`benchmark-floor.sh:70-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L70-L71).
* **Intentional failure in `build.yml`:** The `build.yml:42-43` step compiles benchmarks but never runs them, ensuring that broken benchmarks are caught during the build phase without producing unreliable data.
* **The `allOpen` requirement:** In [`build.gradle.kts:46-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L46-L51), the `allOpen` plugin is used to make `@State` classes non-final, which is necessary because JMH generates subclasses of these classes during execution.
