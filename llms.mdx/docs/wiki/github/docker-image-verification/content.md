# Docker image verification and conformance (/wiki/github/docker-image-verification)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant CI as CI Runner
    participant Build as Docker Build
    participant Image as booblik:ci Image
    participant Smoke as docker-smoke.sh
    participant Conf as conformance/run.sh

    CI->>Build: ./ci/docker-build.sh
    Build->>Image: Create container with baked-in profile
    CI->>Smoke: ./ci/docker-smoke.sh (METADATA check)
    Smoke->>Image: Verify health via protocol
    CI->>Conf: ./conformance/run.sh (Reference Client)
    Conf->>Image: Execute protocol-level checks"
/>

## The `booblik:ci` image and the six profile flags [#the-booblikci-image-and-the-six-profile-flags]

Verification of the runtime environment and the baked-in startup profile. The image is not just a wrapper for the JAR; it contains a specific set of six profile flags baked into the start script. As noted in [`build.yml:58-61`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L58-L61), this is critical because a single `JAVA_OPTS` line in a user's `Dockerfile` could silently replace these flags, resulting in a process that no one has actually measured.

## The `BOOBLIK_SKIP_GATE` mechanism [#the-booblik_skip_gate-mechanism]

The lifecycle of the image build process and how it avoids redundant JVM-side checks. To optimize CI time, the image build step skips the standard gate because the `build` workflow has already executed it on the same commit, as described in [`build.yml:79-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L79-L80). This is implemented by passing `BOOBLIK_SKIP_GATE: '1'` to the build script.

## The `conformance/run.sh` kit [#the-conformancerunsh-kit]

The mechanics of the reference client testing the live broker image. This kit uses a reference client to ensure the broker adheres to the specification, specifically catching errors like a partitioner that disagrees with the protocol, which unit tests of the client alone would never detect ([`client-go.yml:74-76`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-go.yml#L74-L76)).

## The `ci/docker-smoke.sh` health check [#the-cidocker-smokesh-health-check]

Verification of the container's operational state via the METADATA protocol. Unlike a simple TCP connection check, the health check asks the broker for `METADATA` to ensure the process is actually responsive and not just holding a socket open in the kernel backlog (`README.md:104-106`).

## Key files [#key-files]

| File                                                                                                                                                                            | Lines     | What is there                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------- |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L82-L84 ".github/workflows/build.yml")   | `82-84`   | Environment variable for skipping the gate during image build |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L138-L140 ".github/workflows/build.yml") | `138-140` | Execution of the sample script in the `dev` directory         |
| `README.md`                                                                                                                                                                     | `121-123` | Description of the six clients and the conformance kit        |

## Behaviour that surprises [#behaviour-that-surprises]

* The `BOOBLIK_SKIP_GATE` environment variable is used to prevent paying for the same check twice during the image build phase ([`build.yml:83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L83)).
* The `conformance` check is the only way to catch a partitioner that disagrees with the specification, as unit tests are insufficient for protocol-level correctness ([`client-go.yml:74-76`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/client-go.yml#L74-L76)).
* A `HEALTHCHECK` that uses the `METADATA` protocol is required because a simple TCP connection check cannot distinguish between a live broker and a hung process (`README.md:104-106`).
