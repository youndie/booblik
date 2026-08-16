# .github (/wiki/github)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `.github` module contains the Continuous Integration (CI) and Continuous Deployment (CD) workflows for the `booblik` repository. It manages the automated verification of the broker's logic, the correctness of its Docker distribution, and the compliance of various language clients against the core protocol.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Build & Core Verification&#x22;
        B[build.yml] -->|Check| G[Gate: JVM & Lint]
        B -->|Smoke| S[Smoke Test Broker]
        B -->|Image| I[Docker Image Build]
        I -->|Conformance| C[Conformance Kit]
    end

    subgraph &#x22;Client Validation&#x22;
        C -->|Verify| D[client-dotnet.yml]
        C -->|Verify| GO[client-go.yml]
        C -->|Verify| J[client-java.yml]
        C -->|Verify| KN[client-kotlin-native.yml]
    end

    subgraph &#x22;Performance&#x22;
        BM[benchmark.yml] -->|Weekly| R[Report Artifacts]
    end"
/>

## Benchmark execution and reporting [#benchmark-execution-and-reporting]

The benchmark suite is designed to detect catastrophic performance collapses rather than subtle regressions. As noted in [`benchmark.yml:10-12`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L10-L12), a hosted runner is a different machine every time, meaning a 20% swing between runs is considered noise rather than a regression. To avoid measuring noise, the workflow runs `mainCiBenchmark` instead of `mainBenchmark` to exclude disk-bound operations like `GroupCommitBenchmark` ([`benchmark.yml:22-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L22-L25)). The suite runs weekly on a schedule ([`benchmark.yml:36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L36)) and uses a "floor" mechanism to fail only if performance drops by an order of magnitude ([`benchmark.yml:74-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L74-L75)).

More: [Benchmark execution and reporting](.github/benchmark-execution-and)

## The build gate and smoke testing [#the-build-gate-and-smoke-testing]

The `build.yml` workflow implements a multi-stage gate to ensure the stability of the JVM implementation. It first executes a `check` job that includes `ktlint` and unit tests via `./ci/gate.sh jvm` ([`build.yml:35-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L35-L36)). Crucially, benchmarks are compiled but never executed during this stage to prevent silent rot ([`build.yml:42-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L42-L43)). Once the JVM logic is verified, the workflow performs a smoke test on the packaged broker using `./ci/smoke.sh` ([`build.yml:47-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L47-L48)) to ensure the distribution is functional.

## Docker image verification and conformance [#docker-image-verification-and-conformance]

To ensure the broker can be shipped as a container, the `image` job in [`build.yml:65-68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L65-L68) builds a Docker image and runs `./ci/docker-smoke.sh` ([`build.yml:89`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L89)). This is followed by a conformance check where the image is tested against a reference client ([`build.yml:99-102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L99-L102)). This process is repeated for each client workflow to ensure that the published image and the client implementations remain in sync with the protocol.

More: [Docker image verification and conformance](.github/docker-image-verification)

## Client-specific gate and conformance workflows [#client-specific-gate-and-conformance-workflows]

Each client has a dedicated workflow to manage its specific toolchain and platform requirements:

| Client        | Workflow                                                                                                                                                                | Toolchain  | Platform Constraints          |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- | ----------------------------- |
| .NET          | [`client-dotnet.yml:31-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-dotnet.yml#L31-L34)               | .NET 8.0.x | N/A                           |
| Go            | [`client-go.yml:31-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-go.yml#L31-L34)                       | Go 1.25    | No dependencies/cache         |
| Java          | [`client-java.yml:31-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-java.yml#L31-L34)                   | JDK 21     | N/A                           |
| Kotlin/Native | [`client-kotlin-native.yml:38-39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-kotlin-native.yml#L38-L39) | JDK 25     | `ubuntu-24.04` and `macos-15` |

The Kotlin/Native client is unique because it must run on both `ubuntu-24.04` and `macos-15` to execute the native binaries ([`client-kotlin-native.yml:38-39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-kotlin-native.yml#L38-L39)), whereas the conformance check for this client is restricted to Linux because GitHub's macOS runners lack a Docker daemon ([`client-kotlin-native.yml:69-72`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-kotlin-native.yml#L69-L72)).

## Key files [#key-files]

| File                                                                                                                                                                                                                   | Lines   | What is there                       |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------- |
| [`…/workflows/benchmark.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/benchmark.yml#L36 ".github/workflows/benchmark.yml")                                  | `36`    | Cron schedule for weekly benchmarks |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L36 ".github/workflows/build.yml")                                              | `36`    | Execution of the JVM gate script    |
| [`…/workflows/client-dotnet.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-dotnet.yml#L33 ".github/workflows/client-dotnet.yml")                      | `33`    | .NET version specification          |
| [`…/workflows/client-go.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-go.yml#L33 ".github/workflows/client-go.yml")                                  | `33`    | Go version specification            |
| [`…/workflows/client-java.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-java.yml#L56-L58 ".github/workflows/client-java.yml")                        | `56-58` | Dual JDK setup (21 and 25)          |
| [`…/workflows/client-kotlin-native.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-kotlin-native.yml#L38 ".github/workflows/client-kotlin-native.yml") | `38`    | Matrix strategy for OS testing      |

## Behaviour that surprises [#behaviour-that-surprises]

* The `build.yml` workflow compiles benchmarks using `:booblik-benchmark:assembleBenchmarks` ([`build.yml:43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L43)) but explicitly refuses to run them to avoid noise from shared runners.
* The `client-kotlin-native.yml` workflow uses a `matrix` to run the `gate` job on both `ubuntu-24.04` and `macos-15` because native binaries are platform-specific ([`client-kotlin-native.yml:38-39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-kotlin-native.yml#L38-L39)).
* In `client-java.yml`, the `setup-java` action is configured to provide two different versions of the JDK simultaneously ([`client-java.yml:56-58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-java.yml#L56-L58)).
