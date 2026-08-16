# Benchmark execution and reporting (/wiki/github/benchmark-execution-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[./gradlew :booblik-benchmark:mainBenchmark] -->|Full Run| B(main configuration)
    A -->|Quick Check| C(quick configuration)
    A -->|CI Check| D(ci configuration)
    A -->|Probes| E(JavaExec Tasks)
    
    B --> F{JMH Execution}
    C --> F
    D --> F
    E --> G[Ordinary Main Programs]
    
    F --> H[RuntimeFootprint Verification]
    F --> I[MeasurementDir Enforcement]
    F --> J[Artifact Generation]"
/>

## The `main` and `quick` configurations [#the-main-and-quick-configurations]

The module provides different levels of rigor to balance the need for valid data against the need for rapid feedback. The `main` configuration is the only one suitable for recording official numbers, utilizing 5 warmups and 10 iterations ([`build.gradle.kts:65-67`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L65-L67)), whereas the `quick` configuration is intended only for order-of-magnitude checks and is not suitable for the official record due to its wide confidence intervals ([`build.gradle.kts:122-124`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L122-L124)).

## The `ci` configuration and the disk barrier [#the-ci-configuration-and-the-disk-barrier]

To prevent noise caused by the I/O variance of hosted runners, the `ci` configuration explicitly excludes `GroupCommitBenchmark` and the `flushEveryAppend` parameter ([`build.gradle.kts:115-116`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L115-L116)). Instead of checking for percentage-based regressions which are unreliable on shared hardware, the CI workflow uses `benchmark-floor.sh` to detect only "collapses" where performance drops by an order of magnitude ([`benchmark.yml:75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L75)).

## The `RuntimeFootprint` verification [#the-runtimefootprint-verification]

To prevent silent invalidation of results where benchmarks run under a different JVM profile than the production broker, the `RuntimeFootprint` object verifies that required flags like `-Xmx64M` and `-XX:+UseSerialGC` are present ([`RuntimeFootprint.kt:24-30`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt#L24-L30)). This ensures that the measured process matches the intended deployment footprint.

## The `MeasurementDir` and filesystem constraints [#the-measurementdir-and-filesystem-constraints]

The benchmark refuses to run on volatile filesystems like `tmpfs` (which is used for `/tmp` on Ubuntu 26.04) because `fsync` behavior on such systems is non-functional ([`build.gradle.kts:32-34`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L32-L34)). Instead, it forces all measurements to be written to an absolute path in the `build/measurements` directory on the physical disk ([`build.gradle.kts:35-39`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L35-L39)).

## The `allOpen` plugin and `@State` classes [#the-allopen-plugin-and-state-classes]

Because JMH generates a subclass of every `@State` class, Kotlin's default `final` class modifier would cause generation to fail with a compiler-like error message ([`build.gradle.kts:46-50`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L46-L50)). The `allOpen` plugin is used to ensure these classes are non-final for the JMH generator.

## The `probe` task lifecycle [#the-probe-task-lifecycle]

Beyond JMH, the module defines several `probe` tasks which are ordinary `JavaExec` programs designed to measure specific behavioral "shapes" rather than averages ([`build.gradle.kts:132-140`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L132-L140)). These include:

| Task                  | Description                                                     |
| --------------------- | --------------------------------------------------------------- |
| `probeDurability`     | M-24: does `msync` promise what `fsync` promises                |
| `probeStartup`        | M-23: how fast recovery scans a log                             |
| `probeSustainedWrite` | M-26: throughput once the log outgrows memory                   |
| `probeLoad`           | M-33/M-34: end-to-end RPS and latency percentiles over a socket |
| `probeRemoteLoad`     | M-38: the same load against a broker on another machine         |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                | Lines     | What is there                                           |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------- |
| [`booblik-benchmark/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L57-L86 "booblik-benchmark/build.gradle.kts")                                                                                                            | `57-86`   | `benchmark` configuration block and target registration |
| [`booblik-benchmark/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/build.gradle.kts#L132-L154 "booblik-benchmark/build.gradle.kts")                                                                                                          | `132-154` | `probes` map and task registration                      |
| [`…/benchmark/RuntimeFootprint.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt#L24-L46 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/RuntimeFootprint.kt") | `24-46`   | JVM argument verification logic                         |

## Behaviour that surprises [#behaviour-that-surprises]

* **`msync` vs `fsync`**: In certain environments, `msync` can appear significantly faster than `fsync` because it may not provide the same durability guarantees, making it an invalid comparison for durability ([`benchmarking.md:157-162`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/benchmarking.md#L157-L162)).
* **`MAPPED` performance**: While `MAPPED` mode is significantly faster for small writes, its performance can be heavily impacted by segment rolling (re-mapping) during long iterations ([`benchmarking.md:130-135`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/benchmarking.md#L130-L135)).
* **`SELECTOR` vs `VIRTUAL_THREADS`**: In the networking layer, `SELECTOR` provides significantly lower latency and higher throughput than `VIRTUAL_THREADS` because `transferTo` blocks on disk I/O, which pins the virtual thread ([`benchmarking.md:396-402`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/docs/benchmarking.md#L396-L402)).
