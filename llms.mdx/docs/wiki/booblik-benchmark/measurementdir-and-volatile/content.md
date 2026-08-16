# MeasurementDir and volatile filesystems (/wiki/booblik-benchmark/measurementdir-and-volatile)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start Measurement] --> B{Check Property booblik.bench.dir}
    B -- Not Set --> C[Default to build/measurements]
    B -- Set --> D[Use provided Path]
    C --> E[Files.createDirectories]
    D --> E
    E --> F[Get FileStore]
    F --> G{Is type in VOLATILE?}
    G -- Yes: tmpfs/ramfs --> H[Throw IllegalStateException]
    G -- No --> I[Create Temp Directory]
    I --> J[Run Probe]
    J --> K[Report storage type in header]"
/>

## MeasurementDir [#measurementdir]

The core mechanism for ensuring measurement validity by preventing tests on RAM-backed storage. The `MeasurementDir` object acts as a gatekeeper for all measuring entry points, ensuring that any directory used for a run is backed by a real device rather than volatile memory ([`MeasurementDir.kt:39-47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L39-L47)).

## The VOLATILE refusal [#the-volatile-refusal]

The logic behind rejecting `tmpfs` and `ramfs` to prevent 'fast' but false results. The module defines a `VOLATILE` set containing `tmpfs` and `ramfs` ([`MeasurementDir.kt:32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L32)), and it explicitly refuses to return a directory if the `FileStore` type matches these, because `fsync` on such systems is a no-op that merely describes memory speed rather than disk performance ([`MeasurementDir.kt:43-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L43-L45)).

## The build/measurements default [#the-buildmeasurements-default]

The mechanism that forces measurements onto the physical disk where the source tree resides. To avoid the trap of `java.io.tmpdir` (which is `tmpfs` on modern Ubuntu), the default path is set to `build/measurements` relative to the project root ([`MeasurementDir.kt:40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L40)), ensuring the measurement lands on the same filesystem where the source tree lives ([`build.gradle.kts:31-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/build.gradle.kts#L31-L35)).

## describe() and filesystem attribution [#describe-and-filesystem-attribution]

How the physical medium is captured and reported in the benchmark header to ensure comparability. The `describe()` function attempts to capture the `FileStore` type and name ([`MeasurementDir.kt:58-61`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L58-L61)), which is then printed in the report header so that results from different hosts can be compared only if their medium is known ([`MeasurementDir.kt:54-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L54-L56)).

## SustainedWriteProbe and the writeback barrier [#sustainedwriteprobe-and-the-writeback-barrier]

How the probe interacts with the filesystem to saturate writeback and expose the difference between mapped and plain paths. The `SustainedWriteProbe` performs a long-running write loop that aims to saturate the kernel's writeback mechanism ([`SustainedWriteProbe.kt:30-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L30-L35)), specifically designed to observe if the memory-mapped path degrades more sharply than the plain path once the log exceeds the machine's physical RAM ([`SustainedWriteProbe.kt:12-18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L12-L18)).

## Key files [#key-files]

| File                     | Lines    | What is there                                                                                       |
| ------------------------ | -------- | --------------------------------------------------------------------------------------------------- |
| `MeasurementDir.kt`      | `29-49`  | Logic for resolving the base directory and validating it is not on a volatile filesystem.           |
| `SustainedWriteProbe.kt` | `40-137` | The main loop for the sustained write probe, including throughput reporting and summary statistics. |
| `build.gradle.kts`       | `34-44`  | Configuration that injects the `booblik.bench.dir` system property into `JavaExec` tasks.           |

## Behaviour that surprises [#behaviour-that-surprises]

* `MeasurementDir.create` will throw an `IllegalStateException` rather than issuing a warning, because a warning is often ignored after the measurement is already believed ([`MeasurementDir.kt:24-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L24-L25)).
* `SustainedWriteProbe` is designed to make the host machine "unpleasant to use" by intentionally saturating the system's writeback ([`SustainedWriteProbe.kt:30-32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L30-L32)).
* The `main` method in `SustainedWriteProbe` reports the machine's physical RAM because the page cache (which the probe competes with) is bounded by RAM, not by the JVM heap ([`SustainedWriteProbe.kt:65-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SustainedWriteProbe.kt#L65-L71)).
