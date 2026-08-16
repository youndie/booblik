# MeasurementDir (/wiki/booblik-benchmark/measurementdir)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

Documentation for the `MeasurementDir` component within the `booblik-benchmark` module, focusing on its role in ensuring measurement integrity by preventing volatile filesystems and providing environmental context.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start: create prefix] --> B{Check Property: booblik.bench.dir}
    B -- Present --> C[Use provided path]
    B -- Absent --> D[Use build/measurements]
    C --> E[Create Directories]
    D --> E
    E --> F[Get FileStore]
    F --> G{Is type in VOLATILE?}
    G -- Yes --> H[Throw IllegalStateException]
    G -- No --> I[Create Temp Directory]
    I --> J[Return Path]"
/>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `MeasurementDir` object is responsible for ensuring that any directory used for benchmarking is backed by physical storage rather than volatile memory. It prevents the "false speed" trap where `fsync` operations return instantly because they are only hitting RAM, as seen in the failure where a probe reported 0.01 ms `fsync` on `tmpfs` ([`MeasurementDir.kt:10-13`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L10-L13)).

## MeasurementDir [#measurementdir]

The core object responsible for creating valid measurement environments. It acts as a guard against invalid measurement mediums, ensuring that the results reflect disk performance rather than memory speed ([`MeasurementDir.kt:27-49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L27-L49)).

## Volatile Filesystem Refusal [#volatile-filesystem-refusal]

The mechanism that prevents measurements on `tmpfs` or `ramfs` to avoid measuring memory instead of disk. The module defines a set of `VOLATILE` filesystems (`tmpfs`, `ramfs`) ([`MeasurementDir.kt:32`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L32)) and will refuse to return a directory if the `FileStore` type matches these, as a measurement on such a medium is considered "fiction" ([`MeasurementDir.kt:33-47`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L33-L47)).

## The `create` Lifecycle [#the-create-lifecycle]

The process of resolving the base directory via `booblik.bench.dir` and validating the `FileStore` type. The `create` function first checks for the `booblik.bench.dir` system property ([`MeasurementDir.kt:40`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L40)); if absent, it defaults to `build/measurements` ([`MeasurementDir.kt:40`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L40)). After creating the base directories ([`MeasurementDir.kt:41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L41)), it validates the `FileStore` to ensure the medium is not volatile before returning a new temporary directory with the provided prefix ([`MeasurementDir.kt:41-49`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L41-L49)).

## Filesystem Description [#filesystem-description]

The `describe` function and its role in providing the medium's identity in report headers. This function attempts to retrieve the `FileStore` for a given path to return a string containing the filesystem type and name ([`MeasurementDir.kt:58-62`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L58-L62)), which is used in probe reports to ensure results are comparable across different hosts ([`MeasurementDir.kt:54-56`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L54-L56)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                          | Lines   | What is there                                                                       |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| [`…/benchmark/MeasurementDir.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L27-L49 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt") | `27-49` | The `MeasurementDir` object containing logic for directory creation and validation. |
| [`…/benchmark/MeasurementDir.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L58-L62 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt") | `58-62` | The `describe` function for filesystem identification.                              |

## Behaviour that surprises [#behaviour-that-surprises]

* The `create` function will throw an `IllegalStateException` rather than issuing a warning if a volatile filesystem is detected, because a warning is often ignored after the number has already been believed ([`MeasurementDir.kt:24-25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L24-L25)).
* The default measurement directory is not the system's temporary directory (`java.io.tmpdir`), but `build/measurements`, to ensure measurements land on a real filesystem where the source tree lives ([`MeasurementDir.kt:22-23`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/MeasurementDir.kt#L22-L23)).
