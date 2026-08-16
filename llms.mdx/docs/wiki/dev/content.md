# dev (/wiki/dev)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `dev` module provides a comprehensive demonstration of the `booblik` ecosystem through four architectural layers, ranging from basic partitioned event streams to complex Kafka-to-booblik relaying. It includes the necessary orchestration via `docker compose` and a suite of validation scripts to assert the correctness of the implementation.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph Layer 4: Relay
        K1[Kafka Topic] <--> R1[Relay In]
        R1 <--> B[booblik Broker]
        B <--> R2[Relay Out]
        R2 <--> K2[Kafka Topic]
    end

    subgraph Layer 3: Projection
        B --> P[Projection Service]
        P --> Q[Query API]
    end

    subgraph Layer 2: Queue
        B --> Q_Topic[Claims Topic]
        Q_Topic --> W1[Worker 0]
        Q_Topic --> W2[Worker 1]
        Q_Topic --> W3[Worker 2]
    end

    subgraph Layer 1: Partitioned Stream
        B --> C1[Consumer 0 - Partition 0]
        B --> C2[Consumer 1 - Partition 1]
        B --> C3[Consumer 2 - Partition 2]
    end"
/>

## Layer 1: Partitioned event stream with consumer-side position [#layer-1-partitioned-event-stream-with-consumer-side-position]

This layer demonstrates how a publisher can distribute events across partitions using a key to ensure all events for a specific user land in the same partition, as described in [`README.md:19-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L19-L22). The consumer is responsible for its own state, using `FileOffsetStore` to persist its position in a file on a volume to ensure at-least-once delivery guarantees ([`README.md:30-33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L30-L33)). This mechanism ensures that if a process restarts, it can resume from its last known position, though it may replay a small number of records if the offset was not saved atomically ([`README.md:35-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L35-L38)).

More: [Layer 1: Partitioned event stream with consumer-side position](dev/layer-partitioned-event)

## Layer 2: Task queue via claims log arbitration [#layer-2-task-queue-via-claims-log-arbitration]

This layer implements a coordination-free task queue where workers compete for tasks by appending claims to a `claims` topic ([`README.md:68-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L68-L70)). Instead of a central coordinator, the order of the log acts as the arbiter; the first claim to land in a partition wins the lease ([`README.md:69-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L69-L70)). A lease is determined by a timestamp written into the claim itself, ensuring that workers with clock skew still reach the same conclusion about whether a lease has expired ([`README.md:72-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L72-L75)). This design avoids the need for a central coordinator but results in a growing claims log and requires workers to read every claim ([`README.md:88-92`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L88-L92)).

## Layer 3: Log-based projection and state reconstruction [#layer-3-log-based-projection-and-state-reconstruction]

The projection layer treats state as a pure function of the log, where the service stores nothing and instead builds its view by replaying the log ([`README.md:129-131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L129-L131)). The lifecycle of a projection is managed through two distinct phases: `replay()` which catches up to the high watermark, and `follow()` which maintains the view as new events arrive ([`README.md:133-136`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L133-L136)). To prevent data corruption, the service does not persist its position separately from the state; instead, it rebuilds the entire view from the beginning to ensure consistency ([`README.md:141-144`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L141-L144)).

## Layer 4: Kafka-to-booblik relaying [#layer-4-kafka-to-booblik-relaying]

This layer provides bidirectional translation between Kafka and the `booblik` protocol, acting as a bridge between ecosystems ([`README.md:164-166`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L164-L166)). The relay is a single module that changes its behavior based on the environment, specifically regarding who is responsible for remembering the position:

| Direction                                                                                                                                                                                                                                                                                                                                                                                                                              | Who remembers the position    |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| Kafka $\to$ booblik                                                                                                                                                                                                                                                                                                                                                                                                                    | Kafka, in a consumer group    |
| booblik $\to$ Kafka                                                                                                                                                                                                                                                                                                                                                                                                                    | `FileOffsetStore` on a volume |
| ([`README.md:174-175`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L174-L175)). While per-key ordering is preserved during the crossing, the Kafka key itself is not stored in the `booblik` wire format and cannot be recovered on the way back ([`README.md:177-180`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L177-L180)). |                               |

## Build and environment configuration [#build-and-environment-configuration]

The project uses Gradle with Kotlin DSL, where `subprojects` are configured to maintain a consistent style using `ktlint` ([`build.gradle.kts:13-18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/build.gradle.kts#L13-L18)). A critical configuration detail is that `repositories { }` blocks defined in subprojects will replace, rather than append to, the repositories defined in `settings.gradle.kts` due to the `PREFER_PROJECT` mode ([`build.gradle.kts:21-24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/build.gradle.kts#L21-L24)). Additionally, the Docker images are explicitly built for the `amd64` platform to avoid manifest errors on `arm64` hosts ([`README.md:209-210`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L209-L210)).

## Key files [#key-files]

| File                                                                                                                                                                               | Lines   | What is there                                                              |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------------------------- |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L1-L224 "dev/README.md")                                          | `1-224` | Detailed documentation of the four architectural layers and known defects. |
| [`dev/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/build.gradle.kts#L1-L37 "dev/build.gradle.kts")                      | `1-37`  | Gradle configuration for subprojects, including plugins and toolchains.    |
| [`dev/check-projection.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/check-projection.sh#L1-L50 "dev/check-projection.sh")             | `1-50`  | Script to validate the projection's ability to rebuild state via replay.   |
| [`dev/check-queue.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/check-queue.sh#L1-L20 "dev/check-queue.sh")                            | `1-20`  | Script to assert that tasks are won by exactly one worker.                 |
| [`dev/check-redistribution.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/check-redistribution.sh#L1-L68 "dev/check-redistribution.sh") | `1-68`  | Script to test task redistribution after a worker is killed.               |
| [`dev/check-relay.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/check-relay.sh#L1-L50 "dev/check-relay.sh")                            | `1-50`  | Script to verify the full round-trip of records through the relays.        |

## Behaviour that surprises [#behaviour-that-surprises]

* **Position Persistence**: The `Projection` service does not persist its position because persisting a position without the state it belongs to can lead to silent corruption; it must rebuild everything from the log to be certain of its state ([`README.md:141-144`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L141-L144)).
* **Task Redistribution**: In the queue implementation, a task is not "released" by a worker shutting down gracefully; instead, the task becomes claimable again simply because the lease in the log expires ([`check-redistribution.sh:7-9`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/check-redistribution.sh#L7-L9)).
* **Repository Overriding**: Declaring `repositories { }` in a subproject does not add to the parent repositories but replaces them, which can lead to "Could not find" errors if the parent repositories are lost ([`build.gradle.kts:21-24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/build.gradle.kts#L21-L24)).
