# The gated Docker build (/wiki/ci/the-gated-docker)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Code Push] --> B{GitHub Actions}
    B --> C[check job: ./ci/gate.sh jvm]
    C --> D[image job: ./ci/docker-build.sh]
    D --> E[ci/docker-smoke.sh]
    E --> F[conformance/run.sh]
    D -.->|if BOOBLIK_SKIP_GATE=1| G[Unchecked Image]"
/>

## The gate and the distribution [#the-gate-and-the-distribution]

To prevent packaging stale or broken code, [`docker-build.sh:22`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L22) executes the full `check` task via Gradle before any packaging occurs. This ensures that the `installDist` stage, which prepares the files for the image ([`docker-build.sh:27-29`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L27-L29)), only operates on a distribution that has passed all tests and linting.

## The `BOOBLIK_SKIP_GATE` mode [#the-booblik_skip_gate-mode]

For local development and debugging, the build can be bypassed using the `BOOBLIK_SKIP_GATE=1` environment variable ([`docker-build.sh:12`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L12)). When this mode is active, the script explicitly changes the build's truthfulness, labeling the resulting image as "WITHOUT THE GATE — from unchecked code" ([`docker-build.sh:25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L25)) instead of the standard "passed the gate" ([`docker-build.sh:19`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L19)).

## The `image` job lifecycle [#the-image-job-lifecycle]

The `image` job in [`build.yml:65`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L65) is decoupled from the `check` job to satisfy two requirements: it requires a Docker daemon which the standard `check` runner lacks ([`build.yml:63`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L63)), and it must not fail the entire CI pipeline if a Docker-specific issue occurs, as the code correctness was already verified in the `check` job ([`build.yml:64`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L64)).

## The `booblik:ci` image verification [#the-booblikci-image-verification]

The verification of the `booblik:ci` image follows a strict sequence: first, the image is built via `./ci/docker-build.sh booblik:ci` ([`build.yml:84`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L84)), then it is subjected to runtime assertions in `ci/docker-smoke.sh` ([`build.yml:89`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L89)), and finally, it is validated against a reference client via `./conformance/run.sh` ([`build.yml:102`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L102)).

## The `booblik-smoke` assertions [#the-booblik-smoke-assertions]

The `ci/docker-smoke.sh` script performs deep inspection of the running container to ensure the runtime environment matches the measured profile. It verifies that:

* The process runs as the `booblik` user ([`docker-smoke.sh:95`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L95)).
* The JVM profile includes exactly six specific flags, such as `-Xmx64M` and `-XX:+UseSerialGC` ([`docker-smoke.sh:76-83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L76-L83)).
* The `booblik-health` tool can successfully query the broker's `METADATA` ([`docker-smoke.sh:87`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L87)).

## The `booblik-smoke` segment sparseness [#the-booblik-smoke-segment-sparseness]

To ensure the storage layer is efficient, [`docker-smoke.sh:104`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L104) checks that segment files are created as sparse files. It verifies that the `BLOCKS` count reported by `stat` is less than 2048, ensuring the segment has not been fully materialized on disk.

## Key files [#key-files]

| File                                                                                                                                                                          | Lines   | What is there                                            |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------- |
| [`ci/docker-build.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L17-L38 "ci/docker-build.sh")                      | `17-38` | Logic for gating, distribution, and image size reporting |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L43-L85 "ci/docker-smoke.sh")                      | `43-85` | Log awaiting mechanism and JVM flag verification         |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/.github/workflows/build.yml#L14-L90 ".github/workflows/build.yml") | `14-90` | Definition of the `check` and `image` CI jobs            |

## Behaviour that surprises [#behaviour-that-surprises]

* The `await_log` function in [`docker-smoke.sh:43`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L43) uses a loop and a local variable to read `docker logs` into a variable before matching, avoiding a SIGPIPE failure that occurs when piping `docker logs` directly into `grep` under `set -o pipefail`.
* The `installDist` stage in [`docker-build.sh:27`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L27) is a prerequisite for `docker build` because the Dockerfile does not contain a build stage; it relies on the files already present in the `build/install` directory.
* The `booblik-health` check in [`docker-smoke.sh:88`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L88) is designed to fail if a port is "healthy" but no process is actually listening on it, by checking a secondary port that should have no service.
