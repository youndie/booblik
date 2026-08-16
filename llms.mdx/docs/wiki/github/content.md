# .github (/wiki/github)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 4. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

This module contains the CI/CD pipelines and release automation for the `booblik` project, covering benchmarking, code verification, Docker image distribution, and Maven artifact publishing.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Verification (CI)&#x22;
        B[Build & Check] -->|Pass| S[Smoke Test]
        S -->|Pass| I[Image Build]
        I -->|Pass| C[Conformance]
    end

    subgraph &#x22;Release (Manual/Tag)&#x22;
        T[Git Tag] -->|Trigger| R[Release Workflow]
        R -->|Check| B
        R -->|Build| I
        R -->|Push| GHCR[GHCR Registry]
    end

    subgraph &#x22;Library Distribution&#x22;
        P[Publish Workflow] -->|Manual| M[Maven Repo]
        P -->|Verify| V[POM & Dependency Integrity]
    end

    subgraph &#x22;Performance&#x22;
        SCH[Weekly Schedule] -->|Trigger| BM[Benchmark Workflow]
        BM -->|Report| ART[Artifacts]
    end"
/>

## Benchmark execution and reporting [#benchmark-execution-and-reporting]

The benchmark suite is designed to catch massive performance collapses rather than subtle regressions. Because hosted runners are inconsistent machines, the workflow [`benchmark.yml:10-12`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L10-L12) warns that a 20% swing between runs is expected and should be ignored. The job runs `mainCiBenchmark` to avoid disk-bound noise and uses a "floor" mechanism to fail only if performance drops by an order of magnitude, as defined in [`benchmark.yml:72-75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L72-L75).

More: [Benchmark execution and reporting](.github/benchmark-execution-and)

## The build gate and smoke testing [#the-build-gate-and-smoke-testing]

The `check` job in [`build.yml:14`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L14) acts as the primary gate, encompassing tests and `ktlint` to ensure local and CI environments remain in sync. This is followed by a smoke test of the packaged broker via `./ci/smoke.sh` as seen in [`build.yml:47-48`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L47-L48). Additionally, the `sample` job ensures that the published client can actually be used by running real-world scenarios like partition splitting and task queueing in [`build.yml:138-140`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L138-L140).

## Docker image lifecycle and GHCR publishing [#docker-image-lifecycle-and-ghcr-publishing]

Docker images are published to GHCR upon receiving a git tag, as specified in [`release.yml:10-13`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L10-L13). The process involves building the image for `amd64` and pushing both the specific version tag and the `:latest` tag, as detailed in [`release.yml:84-89`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L84-L89). Before pushing, the image undergoes a smoke test via `./ci/docker-smoke.sh` to ensure the container is functional, as seen in [`release.yml:71-72`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L71-L72).

More: [Docker image lifecycle and GHCR publishing](.github/docker-image-lifecycle)

## Maven dependency verification and multiplatform publishing [#maven-dependency-verification-and-multiplatform-publishing]

Publishing is a manual decision that targets JVM and Native libraries. Because multiplatform modules must be published from a single host to maintain target consistency, the workflow runs on `macos-15` as seen in [`publish.yml:39`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/publish.yml#L39). The process includes a rigorous verification step that checks POM files to ensure dependencies like `booblik-core` and `kotlinx-coroutines-core-jvm` are correctly exposed at compile scope, preventing unresolvable dependencies for consumers, as described in [`publish.yml:114-144`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/publish.yml#L114-L144).

## Key files [#key-files]

| File                                                                                                                                                                                      | Lines   | What is there                                       |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------- |
| [`…/workflows/benchmark.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L33-L36 ".github/workflows/benchmark.yml") | `33-36` | Weekly cron schedule for benchmarks                 |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L14-L16 ".github/workflows/build.yml")             | `14-16` | The `check` job configuration                       |
| [`…/workflows/publish.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/publish.yml#L39 ".github/workflows/publish.yml")           | `39`    | The `macos-15` runner requirement for multiplatform |
| [`…/workflows/release.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L10-L13 ".github/workflows/release.yml")       | `10-13` | Triggering via git tags                             |

## Behaviour that surprise [#behaviour-that-surprise]

* The `mainCiBenchmark` task is used instead of `mainBenchmark` to avoid noise from disk-bound operations like `GroupCommitBenchmark` and `SegmentAppendBenchmark`, as noted in [`benchmark.yml:22-24`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/benchmark.yml#L22-L24).
* The `sample` job is the only one that exercises the **published** client against the **published** image to catch packaging mistakes that a standard build would miss, as explained in [`build.yml:104-109`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L104-L109).
* A `publish` action can be triggered from any ref, which is why a `check` step is explicitly included to prevent publishing broken code, as stated in [`publish.yml:60-62`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/publish.yml#L60-L62).
