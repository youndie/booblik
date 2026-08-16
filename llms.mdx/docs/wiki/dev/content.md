# dev (/wiki/dev)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

This module provides a demonstration of distributed systems patterns using the `booblik` broker. It implements four distinct architectural layers: partition-based event streaming, a coordinator-less task queue, stateful projections, and a Kafka relay.

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
        P --> S[State/View]
    end

    subgraph Layer 2: Queue
        B --> Q[Task Queue]
        Q --> W1[Worker 0]
        Q --> W2[Worker 1]
        Q --> W3[Worker 2]
    end

    subgraph Layer 1: Partitioned Stream
        B --> C1[Consumer 0 / Partition 0]
        B --> C2[Consumer 1 / Partition 1]
        B --> C3[Consumer 2 / Partition 2]
    end"
/>

## Partitioning by key and consumer-side position [#partitioning-by-key-and-consumer-side-position]

In the first layer, the system demonstrates how to achieve ordered processing for specific entities. The key (e.g., a user ID) is used by the client to determine the partition number, ensuring all events for a single user land in the same partition ([`README.md:19-22`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L19-L22)). Unlike Kafka consumer groups, the assignment here is static and managed via the environment. Crucially, the consumer is responsible for its own position using a `FileOffsetStore`, which persists the offset to a volume via an atomic move to ensure that a crash during checkpointing does not result in corrupted state ([`README.md:30-33`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L30-L33)).

More: [Partitioning by key and consumer-side position](dev/partitioning-key-and)

## The claims log as a coordinator-less task queue [#the-claims-log-as-a-coordinator-less-task-queue]

Layer 2 implements a task queue where workers compete for tasks without a central coordinator. Instead of locks, workers append "claims" to a specific partition of a `claims` topic; the first claim to land in the log wins ([`README.md:68-70`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L68-L70)). A lease is determined by a timestamp written into the claim itself, meaning workers reach the same conclusion about a lapsed lease even if their local clocks are skewed ([`README.md:72-75`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L72-L75)). This design avoids a central coordinator but introduces specific trade-offs:

* **At-least-once delivery:** A worker might finish a task after its lease has expired and another worker has taken it ([`README.md:85-87`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L85-L87)).
* **Log growth:** Every task requires two records in the claims log ([`README.md:88`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L88)).
* **Scaling limits:** Traffic scales as `workers × tasks` because every worker must read every claim ([`README.md:91-92`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L91-L92)).

## State as a fold over the log [#state-as-a-fold-over-the-log]

The projection layer treats state as a pure function of the log. The service does not persist its own state; instead, it rebuilds its view by replaying the log from the beginning ([`README.md:129-131`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L129-L131)). To ensure no data is lost or duplicated during a restart, the service uses a two-phase approach: `replay()` catches up to the high watermark, and `follow()` continues from that point ([`README.md:133-136`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L133-L136)). A critical safety feature is that the service does not persist its position separately from the state; persisting a position without the corresponding state would lead to silent corruption ([`README.md:141-144`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L141-L144)).

More: [State as a fold over the log](dev/state-fold-over)

## Relay between Kafka and booblik [#relay-between-kafka-and-booblik]

Layer 4 provides a bidirectional bridge between Kafka and `booblik`. Because `booblik` does not implement the full Kafka protocol (such as `baseOffset` and CRC), the relay performs translation in user space ([`README.md:162-166`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L162-L166)). The direction of data flow is determined by the environment configuration. The position management differs by direction:

| Direction                                                                                                                         | Who remembers the position                    |
| --------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| Kafka $\to$ booblik                                                                                                               | Kafka (via consumer group, auto-commit off)   |
| booblik $\to$ Kafka                                                                                                               | The relay (via `FileOffsetStore` on a volume) |
| ([`README.md:174-175`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L174-L175)) |                                               |

## Build and dependency configuration [#build-and-dependency-configuration]

The module uses Gradle with Kotlin DSL. The `subprojects` block in [`build.gradle.kts:7-38`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/build.gradle.kts#L7-L38) applies the Kotlin JVM and serialization plugins to all subprojects. A significant configuration detail is the avoidance of `repositories { }` blocks within `subprojects`, as doing so would replace rather than append to the repositories defined in `settings.gradle.kts` ([`build.gradle.kts:21-24`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/build.gradle.kts#L21-L24)). Additionally, the broker's runtime profile (e.g., 64 MiB memory constraints) is intentionally not applied to the sample services to ensure the benchmarks reflect the service's actual work rather than the broker's constraints ([`build.gradle.kts:31-37`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/build.gradle.kts#L31-L37)).

## Key files [#key-files]

| File                                                                                                                                                                               | Lines   | What is there                                                              |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------------------------- |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L1-L224 "dev/README.md")                                          | `1-224` | Detailed architectural documentation of the four layers and known defects. |
| [`dev/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/build.gradle.kts#L1-L38 "dev/build.gradle.kts")                      | `1-38`  | Gradle configuration for subprojects and dependency management.            |
| [`dev/check-projection.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-projection.sh#L1-L50 "dev/check-projection.sh")             | `1-50`  | Script to verify projection replay and state consistency.                  |
| [`dev/check-queue.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-queue.sh#L1-L20 "dev/check-queue.sh")                            | `1-20`  | Script to assert task ownership in the queue layer.                        |
| [`dev/check-redistribution.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-redistribution.sh#L1-L68 "dev/check-redistribution.sh") | `1-68`  | Script to test task redistribution after a worker is killed.               |
| [`dev/check-relay.sh`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/check-relay.sh#L1-L50 "dev/check-relay.sh")                            | `1-50`  | Script to verify the Kafka $\leftrightarrow$ booblik round trip.           |

## Behaviour that surprises [#behaviour-that-surprises]

* **Silent Replay:** If a `FileOffsetStore` is truncated, it can cause a consumer to silently replay records because the parser handles truncated strings without error ([`README.md:32-33`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L32-L33)).
* **The `partitionFor(null)` Trap:** Calling `partitionFor(null)` advances an internal counter, which can cause records to skip partitions if the user calls the function once to "check" and then again to "send" ([`README.md:204-206`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L204-L206)).
* **The `receive` Cancellation Bug:** In version `0.1.2`, cancelling a `mailbox.receive` call could pull an element off a channel and drop it, causing the producer to hang while the element was lost ([`README.md:219-222`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/README.md#L219-L222)).
