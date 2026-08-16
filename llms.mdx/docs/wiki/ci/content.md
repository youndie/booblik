# ci (/wiki/ci)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Code Change] --> B[ci/gate.sh]
    B -- Passed --> C[ci/docker-build.sh]
    C --> D[ci/docker-smoke.sh]
    D -- Success --> E[Deployment]
    B -- Passed --> F[ci/remote-run.sh]
    F --> G[ci/benchmark-floor.sh]"
/>

## The Gate [#the-gate]

The Gate acts as the primary entry point for project verification, orchestrating various checks to ensure the codebase is stable. It is designed to be language-agnostic, allowing different clients to define their own requirements via `gate.sh` (as seen in the loop starting at [`gate.sh:109`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L109)). A critical distinction is made between a failure and a skip: if a required toolchain is missing, the part is marked as skipped using exit code 77 ([`gate.sh:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L24)), ensuring that a machine without a Go toolchain does not report a broken Go client ([`gate.sh:133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L133)).

More: [The Gate](ci/the-gate)

## The Gatekeeper [#the-gatekeeper]

To prevent shipping stale or broken code, the build process follows a strict order: first, the code must pass the gate, and only then is a distribution created and packaged into a Docker image ([`docker-build.sh:5-7`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-build.sh#L5-L7)). This prevents the common error of building an image from a local `build/install` directory that might contain unverified or old artifacts ([`docker-build.sh:6`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-build.sh#L6)). The script explicitly tracks whether the image was built from a "gated" distribution or from "unchecked code" via the `BOOBLIK_SKIP_GATE` environment variable ([`docker-build.sh:20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-build.sh#L20)).

More: [The Gatekeeper](ci/the-gatekeeper)

## The Smoke Test [#the-smoke-test]

The smoke test verifies the actual delivery of the software by running the distribution in a container and performing end-to-end socket communication ([`smoke.sh:4`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L4)). It validates several runtime properties:

* **Connectivity**: The broker must listen on the configured port ([`smoke.sh:41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L41)).
* **Configuration**: Environment variables like `BOOBLIK_TOPICS` must be applied to the process ([`smoke.sh:65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L65)).
* **JVM Profile**: The process must run with specific flags like `-Xmx64M` and `-XX:+UseSerialGC` to ensure the measured profile matches the intended one ([`docker-smoke.sh:76`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L76)).
* **Persistence**: Data must land on the volume and maintain sparseness ([`docker-smoke.sh:104`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L104)).

More: [The Smoke Test](ci/the-smoke-test)

## The Performance Floor [#the-performance-floor]

Rather than detecting fine-grained regressions which are unreliable on shared CI runners, this module implements a "floor" to catch catastrophic collapses in performance ([`benchmark-floor.sh:11`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L11)). It compares measured throughput against a threshold that is an order of magnitude below the slowest known hardware ([`benchmark-floor.sh:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L24)). This ensures that if a change makes writes a hundred times slower, it is caught, while ignoring the 37% variance caused by different machines ([`benchmark-floor.sh:6`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L6)).

## Remote Execution [#remote-execution]

For high-fidelity measurements, tasks can be executed on remote Linux hosts via [`remote-run.sh:4`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L4). To ensure the integrity of performance measurements, the script performs a storage probe to verify the filesystem type ([`remote-run.sh:48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L48)) and refuses to run if the directory sits on network storage like `nfs` or `cifs` ([`remote-run.sh:62`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L62)). It also warns the user if the disk is rotational, as such hardware cannot be compared to NVMe runs ([`remote-run.sh:68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L68)).

## Key files [#key-files]

| File                                                                                                                                                              | Lines   | What is there                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------- |
| [`ci/benchmark-floor.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/benchmark-floor.sh#L24-L25 "ci/benchmark-floor.sh") | `24-25` | Threshold constants for `FILE_CHANNEL` and `MAPPED` modes |
| [`ci/docker-build.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-build.sh#L17 "ci/docker-build.sh")              | `17`    | The `IMAGE` variable for the target Docker tag            |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L76-L77 "ci/docker-smoke.sh")          | `76-77` | The list of required JVM flags for the runtime profile    |
| [`ci/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L24 "ci/gate.sh")                                      | `24`    | The `SKIPPED_CODE` constant (77)                          |
| [`ci/remote-run.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L62-L66 "ci/remote-run.sh")                | `62-66` | Prohibited filesystem types for measurement integrity     |
| [`ci/smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L20-L28 "ci/smoke.sh")                               | `20-28` | Configuration properties for the smoke test broker        |

## Behaviour that surprise [#behaviour-that-surprise]

* The `part` function in [`gate.sh:54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L54) uses a specific logic where it avoids reporting a failure as a success by checking the exit code after the command execution, preventing "successful failures" from being reported as exit 0.
* In [`docker-smoke.sh:40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L40), the script avoids using `grep -q` in a pipeline because `set -o pipefail` would cause a successful match to return a non-zero exit code due to the `SIGPIPE` sent to the writer.
* The `remote-run.sh` script uses `printf ' %q' "${TASKS[@]}"` ([`remote-run.sh:76`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/remote-run.sh#L76)) to ensure that complex arguments are correctly escaped when passed through the SSH transport to the remote shell.
