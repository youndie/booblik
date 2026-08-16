# Docker image lifecycle and GHCR publishing (/wiki/github/docker-image-lifecycle)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant G as Git Tag (v*)
    participant R as release.yml
    participant B as docker-build.sh
    participant S as docker-smoke.sh
    participant H as GHCR

    G->>R: Trigger workflow
    R->>R: Determine version & image name
    R->>R: Run ./gradlew check (Gate)
    R->>B: Build image from installDist
    B->>B: Run ./gradlew :booblik-app:installDist
    R->>S: Run smoke test on image
    S->>S: Verify JVM flags & User
    S->>S: Check segment sparseness
    R->>H: Push versioned tag
    R->>H: Push :latest:"
/>

## The `release` workflow and tag-based publishing [#the-release-workflow-and-tag-based-publishing]

The publishing process is triggered by git tags matching the `v*` pattern or via manual dispatch [`release.yml:12`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L12). The workflow performs a critical transformation of the repository name into a lowercase registry path to comply with container registry requirements [`release.yml:54`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L54). The version is derived by stripping the leading 'v' from the git tag, ensuring that a tag like `v0.1.0` results in an image tagged `:0.1.0` [`release.yml:50`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L50).

## The `check` gate and distribution integrity [#the-check-gate-and-distribution-integrity]

To prevent the "shipping of unchecked code," the `release` workflow enforces a strict order of operations. It first runs `./gradlew check` as a gate [`release.yml:62`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L62). The `ci/docker-build.sh` script is designed to respect this by explicitly running the gate before performing the `:booblik-app:installDist` task [`docker-build.sh:21-27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-build.sh#L21-L27). This ensures the image is built from a distribution that has already passed all tests, rather than building from a potentially broken local workspace [`docker-build.sh:5-7`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-build.sh#L5-L7).

## The `docker-smoke` conformance kit [#the-docker-smoke-conformance-kit]

The `ci/docker-smoke.sh` script performs deep inspection of the running container to ensure the runtime environment matches the measured profile. It validates that the process runs as the `booblik` user rather than `root` [`docker-smoke.sh:95`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-smoke.sh#L95). Crucially, it parses the `jvm:` line in the logs to verify that six specific flags—including `-Xmx64M` and `-XX:MaxDirectMemorySize=32M`—are present [`docker-smoke.sh:76-83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-smoke.sh#L76-L83). It also verifies that the broker's health check responds to `METADATA` requests [`docker-smoke.sh:87`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-smoke.sh#L87).

## The `sparse` segment and volume persistence [#the-sparse-segment-and-volume-persistence]

A specific edge case is tested to ensure that the storage engine's performance characteristics are preserved in the image. The smoke test checks that when a segment is created, it remains "sparse" on the filesystem [`docker-smoke.sh:104`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-smoke.sh#L104). It does this by inspecting the `stat` output of the segment file to ensure the allocated blocks are significantly lower than the apparent file size [`docker-smoke.sh:102-104`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-smoke.sh#L102-L104).

## The `latest` tag and registry synchronization [#the-latest-tag-and-registry-synchronization]

Once the image is verified, the workflow performs a dual-push to GHCR. It tags the specific versioned image (e.g., `:0.1.0`) and the `:latest` pointer [`release.yml:87-89`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L87-L89). This ensures that users can always pull the most recent stable version via the `:latest` tag while maintaining a permanent record of specific releases [`release.yml:88`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L88).

## Key files [#key-files]

| File                                                                                                                                                                                | Lines   | What is there                                            |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------- |
| [`…/workflows/release.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L10-L23 ".github/workflows/release.yml") | `10-23` | Workflow trigger conditions and manual input definitions |
| [`…/workflows/release.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/release.yml#L39-L58 ".github/workflows/release.yml") | `39-58` | Version extraction logic and registry path formatting    |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L76-L83 "ci/docker-smoke.sh")                            | `76-83` | JVM runtime profile flag verification                    |
| [`ci/docker-build.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L21-L26 "ci/docker-build.sh")                            | `21-26` | Gate execution logic                                     |
| `README.md`                                                                                                                                                                         | `69-72` | The specific JVM profile required for measurement        |

## Behaviour that surprises [#behaviour-that-surprises]

* **The `pipefail` trap**: In [`docker-smoke.sh:39`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L39), using `grep` in a pipeline with `set -o pipefail` can cause a successful match to return a failure code (141) because `grep` exits early and sends a `SIGPIPE` to `docker logs`.
* **The `installDist` dependency**: The `ci/docker-build.sh` script does not use a multi-stage Dockerfile; instead, it relies on the host to run `:booblik-app:installDist` first to ensure the image contains the exact distribution that passed the gate [`docker-build.sh:27-32`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.ci/docker-build.sh#L27-L32).
