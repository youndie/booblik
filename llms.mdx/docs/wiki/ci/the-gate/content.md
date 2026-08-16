# The Gate (/wiki/ci/the-gate)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The Gate is the unified entry point for all quality checks in the project. It ensures that every component—from the JVM core to various language clients—is verified through a consistent process. It is designed to be the single command a developer runs to ensure the entire system is sound, preventing the "rotting" of CI scripts by ensuring the same script used locally is the one used in automation ([`gate.sh:7-8`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L7-L8)).

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[./ci/gate.sh] --> B[JVM Check]
    A --> C[Client Gates]
    A --> D[Conformance Run]
    
    subgraph &#x22;Client Loop&#x22;
    C --> C1[gate.sh]
    C1 -->|If passed| C2[conformance-client.sh]
    C2 --> C3[Docker Conformance]
    end
    
    B -->|If passed| D
    C3 --> D"
/>

## The `part` mechanism [#the-part-mechanism]

The `part` function is the engine of the gate, responsible for executing a command, capturing its exit code, and managing output. When running in a terminal, it streams output to the user and shows the last 25 lines of the log if a failure occurs ([`gate.sh:59-81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L59-L81)), whereas in CI environments, it streams everything with a prefix for readability ([`gate.sh:64-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L64-L65)). It also handles the logic of checking if a required toolchain is available before attempting execution ([`gate.sh:47-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L47-L51)).

## The `SKIPPED_CODE` convention [#the-skipped_code-convention]

To avoid the "false red" problem where a missing toolchain is reported as a failure, the gate uses a specific exit code, `77`, to represent a skipped task ([`gate.sh:24-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L24-L25)). This follows the automake convention, allowing a machine without a specific toolchain (like Go) to report that a part was skipped rather than failed, ensuring the report remains honest ([`gate.sh:15-17`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L15-L17)).

## The `selected` filter [#the-selected-filter]

The script allows users to run only specific subsets of the gate by passing arguments. The `selected` function iterates through the provided arguments to determine if a specific language or component should be executed ([`gate.sh:89-97`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L89-L97)). This allows a developer to run only the `jvm` part or a specific client like `go` (`ci/gate.sh:101, 112`).

## The `conformance-client.sh` contract [#the-conformance-clientsh-contract]

For a client to participate in the end-to-end conformance run, it must provide a `conformance-client.sh` executable within its directory ([`README.md:78-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L78-L80)). This script must implement a specific contract: it must support `argv` verbs, output `key=value` pairs to stdout, and respect the `BOOBLIK_BROKER` environment variable ([`README.md:82-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L82-L83)).

## The `LAST_RESULT` lifecycle [#the-last_result-lifecycle]

The gate maintains a strict dependency chain to ensure resources are not wasted on broken code. A client's conformance run (which requires a live Docker container) is only triggered if the client's own `gate.sh` has successfully passed ([`gate.sh:118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L118)). This prevents the system from attempting to run a conformance test against a client that is already known to be broken by its own unit tests ([`gate.sh:115-116`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L115-L116)).

## Key files [#key-files]

| File                                                                                                                                                  | Lines     | What is there                                                         |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------------------------------------------- |
| [`ci/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L24-L35 "ci/gate.sh")                      | `24-35`   | Definition of `SKIPPED_CODE` and the `LAST_RESULT` state tracking.    |
| [`ci/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L42-L86 "ci/gate.sh")                      | `42-86`   | The `part` function implementation and exit code handling.            |
| [`clients/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L77-L83 "clients/README.md") | `77-83`   | The requirements and contract for `conformance-client.sh`.            |
| `build.gradle.kts`                                                                                                                                    | `120-121` | Wiring the `checkKotlinAbi` task into the standard `check` lifecycle. |

## Behaviour that surprises [#behaviour-that-surprises]

* **The `part` function** does not use `set -e` because it needs to continue running all parts to generate a complete report, even if one fails ([`gate.sh:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L19)).
* The `conformance` run is the only part that can catch a client that is "self-consistently wrong" by testing it against a real broker ([`gate.sh:107-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L107-L108)).
* The `booblik-benchmark` module is explicitly excluded from the standard `check` task to prevent API changes in the benchmark from triggering unnecessary ABI validation failures (`build.gradle.kts:105-107`).
