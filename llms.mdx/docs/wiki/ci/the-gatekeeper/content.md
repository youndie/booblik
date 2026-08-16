# The Gatekeeper (/wiki/ci/the-gatekeeper)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start: gate.sh] --> B{Selected Parts?}
    B -->|jvm| C[gradlew check]
    B -->|clients| D[Client Gate: gate.sh]
    B -->|conformance| E[Reference Client Check]
    
    C -->|Pass| D
    D -->|Pass| E
    
    subgraph &#x22;Client Lifecycle&#x22;
    D -->|Unit Tests| F[conformance-client.sh]
    F -->|Docker| E
    end
    
    E -->|Success| G[Gate Passed]"
/>

## The `gate.sh` execution loop [#the-gatesh-execution-loop]

The core of the gate is the `part` function, which encapsulates the execution of a command and its reporting [`gate.sh:43-85`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L43-L85). To maintain honesty in environments where certain toolchains are missing, the script utilizes the `SKIPPED_CODE` (77) convention [`gate.sh:24-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L24-L35). This allows the script to report that a part was "skipped" rather than "failed," preventing a machine without a Go toolchain from reporting a broken Go client when the developer simply hasn't installed the necessary dependencies [`gate.sh:132-142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L132-L142).

## The `jvm` and `client` lifecycle [#the-jvm-and-client-lifecycle]

The execution follows a strict dependency chain to ensure efficiency and correctness. The `jvm` part runs the core Gradle checks [`gate.sh:101`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L101). For any client module, the gate first executes the client's own internal `gate.sh` [`gate.sh:109-114`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L109-L114). A client's conformance check is only triggered if its own unit tests pass and a conformance binary exists [`gate.sh:117-120`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L117-L120). To prevent redundant work during the Docker-based image build, the `BOOBLIK_SKIP_GATE` environment variable is used to skip the redundant execution of the gate within the containerized environment [`gate.sh:39-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L39-L40).

## The `conformance` check [#the-conformance-check]

The final and most heavy-weight stage is the `conformance` check, which validates the reference client against a live broker [`gate.sh:124`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L124). This stage is unique because it requires both a Docker daemon to run the broker and a Gradle environment to manage the test orchestration. It is the only part of the gate that performs an end-to-end verification of the protocol by running a client against a real, running instance of the broker [`gate.sh:119`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L119).

## The `check` job and the `image` job separation [#the-check-job-and-the-image-job-separation]

The CI architecture splits the verification into two distinct jobs in [`build.yml:14-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L14-L90). The `check` job runs on a standard runner to perform JVM-based tests and smoke tests without requiring Docker [`build.yml:35-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/github/workflows/build.yml#L35-L48). The `image` job is separated because it requires a Docker daemon and is responsible for verifying the actual containerized process, including the six specific JVM profile flags [`docker-smoke.sh:73-84`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L73-L84). This separation ensures that environmental issues in the Docker layer do not mask code-level failures in the JVM layer.

## Key files [#key-files]

| File                                                                                                                                                                          | Lines     | What is there                                    |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------ |
| [`ci/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L43-L85 "ci/gate.sh")                                              | `43-85`   | The `part` function logic and exit code handling |
| [`ci/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L109-L121 "ci/gate.sh")                                            | `109-121` | The client lifecycle and conformance loop        |
| [`…/workflows/build.yml`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/.github/workflows/build.yml#L14-L90 ".github/workflows/build.yml") | `14-90`   | The separation of `check` and `image` jobs       |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L73-L84 "ci/docker-smoke.sh")                      | `73-84`   | Verification of the six JVM profile flags        |

## Behaviour that surprises [#behaviour-that-surprises]

* The `part` function uses a `case` statement to handle exit codes, ensuring that a command's failure is captured correctly even when using `PIPESTATUS` in a pipeline [`gate.sh:65-67`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L65-L67).
* The `gate.sh` script uses a `trap` to ensure that the temporary directory used for storing logs is cleaned up upon exit [`gate.sh:26`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/gate.sh#L26).
* In `ci/docker-smoke.sh`, the script uses a `for` loop to iterate through a specific list of JVM flags to ensure the runtime profile matches the measured requirements [`docker-smoke.sh:76-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L76-L83).
