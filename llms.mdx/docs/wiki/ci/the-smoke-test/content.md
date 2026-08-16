# The Smoke Test (/wiki/ci/the-smoke-test)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The Smoke Test module ensures that the built distribution and the resulting Docker image are actually functional in a real-world runtime environment. While unit tests verify logic, the smoke tests verify **delivery**: they ensure that configuration is correctly read, the `main` method doesn't die at startup, and the containerized environment preserves the intended runtime profile and security constraints.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant B as Broker Process
    participant P as Python Client
    participant D as Docker Container
    participant H as Health Check

    Note over B, P: ci/smoke.sh
    P->>B: PRODUCE (Binary Protocol)
    B-->>P: Response (Correlation/Error)
    P->>B: FETCH (Binary Protocol)
    B-->>P: Payload (Records + CRC32C)
    P->>P: Verify Checksums
    B->>B: Kill Process
    B->>B: Restart Broker
    B->>P: Verify Log Recovery (Offsets)

    Note over D, H: ci/docker-smoke.sh
    D->>D: Start with BOOBLIK_TOPICS
    D->>D: Verify JVM Flags (Xmx, Xss, etc.)
    D->>D: Verify User (non-root)
    D->>D: Verify Sparse Segment (ls/stat)
    H->>D: METADATA Check
    D-->>H: Live Port"
/>

## The Distribution Lifecycle [#the-distribution-lifecycle]

The smoke test starts by building the full distribution using the Gradle `installDist` task ([`smoke.sh:31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L31)). Once built, it launches the broker as a background process and waits for the "booblik listening" log entry to confirm it has successfully bound to the network ([`smoke.sh:41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L41)). The lifecycle concludes by killing the broker and starting a second instance to ensure the system can transition from a stopped to a running state without losing data ([`smoke.sh:131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L131)).

## The Wire Protocol and CRC32C Verification [#the-wire-protocol-and-crc32c-verification]

To ensure the implementation hasn't drifted from the specification, a Python script is used to communicate over a raw socket ([`smoke.sh:47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L47)). This script is intentionally written in a language that shares no code with the Kotlin implementation, acting as an independent validator of the binary format ([`smoke.sh:51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L51)). It performs the following checks:

| Field           | Type    | Description                      |
| --------------- | ------- | -------------------------------- |
| `length`        | `int32` | Size of the body                 |
| `apiKey`        | `int16` | Request type                     |
| `apiVersion`    | `int16` | Protocol version                 |
| `correlationId` | `int32` | Request/Response matching        |
| `crc32c`        | `int32` | Per-record checksum (Castagnoli) |

The client manually calculates the CRC32C for each record and asserts that the broker's returned checksum matches the expected value ([`smoke.sh:125`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L125)).

## The Log Recovery and Restart Mechanism [#the-log-recovery-and-restart-mechanism]

The tests verify that the append-only log is durable across process restarts. After the first broker instance is killed, a new instance is started using the same properties file ([`smoke.sh:133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L133)). The test then verifies that the log segment is recovered by checking that the broker reports the correct offset range for the "smoke" topic ([`smoke.sh:139`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L139)).

## The Docker Runtime Profile [#the-docker-runtime-profile]

The `ci/docker-smoke.sh` script validates that the Docker image preserves the specific resource constraints required for the broker's performance profile. It extracts the `jvm:` line from the container logs and verifies that all six mandatory flags are present ([`docker-smoke.sh:75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L75)). These flags include:

| Flag                            | Purpose                     |
| ------------------------------- | --------------------------- |
| `-XX:+UseSerialGC`              | Garbage Collection strategy |
| `-XX:ReservedCodeCacheSize=32M` | Code cache limit            |
| `-XX:MaxDirectMemorySize=32M`   | Direct memory limit         |
| `-Xss256k`                      | Thread stack size           |
| `-XX:MaxMetaspaceSize=80M`      | Metaspace limit             |
| `-Xmx64M`                       | Heap memory limit           |

Additionally, it ensures the process does not run as `root` by checking the `id -un` output ([`docker-smoke.sh:95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L95)).

## Sparse Segment Materialization [#sparse-segment-materialization]

To optimize disk usage, the broker uses sparse files for its segments. The smoke test executes a command inside the container to locate the latest `.log` segment and uses `stat` to check its apparent size versus its actual disk occupation ([`docker-smoke.sh:102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L102)). It asserts that the actual blocks occupied are significantly less than the apparent size, confirming that the segment is indeed sparse ([`docker-smoke.sh:104`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L104)).

## The Health Check and METADATA Interface [#the-health-check-and-metadata-interface]

The health check mechanism is tested to ensure it provides meaningful status. It validates that the `booblik-health` tool correctly identifies a live broker via the `METADATA` interface ([`docker-smoke.sh:87`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L87)). Crucially, it also verifies that the health check does not report a "healthy" status if it is attempting to connect to a port that is not actually listening ([`docker-smoke.sh:88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L88)).

## Key files [#key-files]

| File                                                                                                                                                       | Lines     | What is there                                    |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------ |
| [`ci/smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/smoke.sh#L47-L128 "ci/smoke.sh")                       | `47-128`  | Python-based independent wire protocol validator |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L75-L83 "ci/docker-smoke.sh")   | `75-83`   | JVM flag profile verification logic              |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L94-L95 "ci/docker-smoke.sh")   | `94-95`   | Non-root user identity check                     |
| [`ci/docker-smoke.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/ci/docker-smoke.sh#L102-L104 "ci/docker-smoke.sh") | `102-104` | Sparse file/segment verification                 |

## Behaviour that surprises [#behaviour-that-surprises]

* The `transferTo` method in the transport layer only provides zero-copy performance when the target is a `SocketChannel` (`README.md:211`).
* The `booblik-app` start script is responsible for baking the JVM profile into the distribution, but this can be silently overridden by a `JAVA_OPTS` line in a `Dockerfile` (`README.md:80`).
* The `booblik-client` is a "shared codec" that is used by both the client and the server to ensure the wire format remains consistent (`README.md:222`).
