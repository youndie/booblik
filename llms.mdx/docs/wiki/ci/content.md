# ci (/wiki/ci)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `ci` module provides a suite of automation scripts designed to ensure the reliability, performance, and correct delivery of the `booblik` broker. It covers everything from detecting catastrophic performance collapses to verifying that Docker images are built from validated distributions and behave correctly in a containerized environment.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Working Tree] -->|rsync| B(Remote Linux / WSL2)
    B -->|gradlew check| C{Gate}
    C -->|Pass| D[gradlew installDist]
    D --> E[docker build]
    E --> F[docker smoke test]
    F --> G[Python Client Test]
    A -->|rsync| H[Remote Run]
    H -->|gradlew benchmark| I[Performance Floor Check]"
/>

## The performance floor [#the-performance-floor]

The mechanism for detecting catastrophic performance collapses without using relative percentages. Instead of using percentages which are unreliable on different hardware, [`benchmark-floor.sh:24-25`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/benchmark-floor.sh#L24-L25) defines absolute thresholds (a tenth of the slowest measured host) to catch "collapses" rather than minor regressions.

More: [The performance floor](ci/the-performance-floor)

## The gated Docker build [#the-gated-docker-build]

The lifecycle stage ensuring images are built only from distributions that have passed all checks. As described in [`docker-build.sh:20-22`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L20-L22), the script runs `gradlew check` to act as a gate, ensuring that the resulting image is built from code that has passed all tests.

More: [The gated Docker build](ci/the-gated-docker)

## The Docker smoke test [#the-docker-smoke-test]

Verification of the container image, including runtime profiles, user permissions, and data sparseness. The script [`docker-smoke.sh:76-83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L76-L83) explicitly verifies that the JVM flags (such as `-Xmx64M`) are present in the running process to ensure the container uses the intended resource profile.

## The remote execution transport [#the-remote-execution-transport]

The mechanism for running tasks on remote Linux machines while avoiding network-induced measurement bias. To ensure measurements reflect the hardware and not the network, [`remote-run.sh:53-66`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/remote-run.sh#L53-L66) refuses to run if the directory sits on a network filesystem like `nfs` or `cifs`.

## The WSL measurement environment [#the-wsl-measurement-environment]

The specific configuration required to run benchmarks in WSL2 to avoid the performance characteristics of the Windows filesystem. The script [`wsl-run.sh:60-61`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/wsl-run.sh#L60-L61) prevents execution if the path is within `/mnt/*`, as measurements on the Windows filesystem (9p) are considered invalid for performance testing.

## The distribution delivery check [#the-distribution-delivery-check]

Testing the broker's ability to serve and survive a restart using a standalone Python client. This is implemented in [`smoke.sh:109-122`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/smoke.sh#L109-L122), where a Python script independently implements the wire format to verify that the broker's `PRODUCE` and `FETCH` operations work correctly and that checksums match.

## Key files [#key-files]

| File                                                                                                                                                              | Lines     | What is there                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------------------------------- |
| [`ci/benchmark-floor.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/benchmark-floor.sh#L24-L25 "ci/benchmark-floor.sh") | `24-25`   | Absolute floor values for FILE\_CHANNEL and MAPPED modes  |
| [`ci/docker-build.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-build.sh#L20-L22 "ci/docker-build.sh")          | `20-22`   | The gate execution logic using `gradlew check`            |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L76-L83 "ci/docker-smoke.sh")          | `76-83`   | Verification of specific JVM runtime flags                |
| [`ci/remote-run.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/remote-run.sh#L53-L66 "ci/remote-run.sh")                | `53-66`   | Logic to reject network-mounted filesystems               |
| [`ci/smoke.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/smoke.sh#L109-L122 "ci/smoke.sh")                             | `109-122` | Python implementation of the wire format for verification |
| [`ci/wsl-run.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/wsl-run.sh#L60-L61 "ci/wsl-run.sh")                         | `60-61`   | Protection against measuring the Windows filesystem       |

## Behaviour that surprise [#behaviour-that-surprise]

* The `await_log` function in [`docker-smoke.sh:43-51`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/docker-smoke.sh#L43-L51) must wait for log patterns because `docker logs` is not synchronous, and reading it once can lead to race conditions.
* In [`remote-run.sh:76`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/remote-run.sh#L76), the `TASKS_QUOTED` variable uses `printf ' %q'` to ensure that complex arguments are parsed correctly by the remote shell rather than being split into multiple arguments.
* The `rsync` command in [`wsl-run.sh:82`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/ci/wsl-run.sh#L82) requires the `--rsync-path` flag to bridge the gap between the Windows host and the WSL2 Linux environment.
