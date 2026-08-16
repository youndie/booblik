# dev/publisher (/wiki/dev-publisher)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Config[PublisherConfig] -->|Environment| Main[Main.kt]
    Main -->|Connection| Broker((Booblik Broker))
    Main -->|Events| Topic[Events Topic]
    Main -->|Tasks| TaskTopic[Tasks Topic]
    Main -->|HTTP Stats| Web[Ktor Server]
    Topic -->|Partitioned by Key| Broker
    TaskTopic -->|Fixed Partition| Broker"
/>

## PublisherConfig [#publisherconfig]

Configuration parameters for broker connection, topic names, and simulation intervals via environment variables, as defined in [`Main.kt:177-200`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L177-L200).

| Variable             | Environment Variable      | Default Value |
| -------------------- | ------------------------- | ------------- |
| `brokerHost`         | `BOOBLIK_HOST`            | `127.0.0.1`   |
| `brokerPort`         | `BOOBLIK_PORT`            | `9092`        |
| `topic`              | `BOOBLIK_TOPIC`           | `events`      |
| `intervalMillis`     | `PUBLISH_INTERVAL_MILLIS` | `1000`        |
| `users`              | `PUBLISH_USERS`           | `9`           |
| `httpPort`           | `HTTP_PORT`               | `8080`        |
| `tasksTopic`         | `BOOBLIK_TASKS_TOPIC`     | `null`        |
| `taskIntervalMillis` | `TASK_INTERVAL_MILLIS`    | `700`         |

## openConnection [#openconnection]

The retry mechanism used to establish a connection to the broker, ensuring the publisher waits for the broker to be ready, implemented in [`Main.kt:76-86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L76-L86).

## publishForever [#publishforever]

The main event loop that simulates user activity by hashing user keys to specific partitions and sending JSON payloads, found in [`Main.kt:88-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L88-L108).

## publishTasks [#publishtasks]

A secondary loop that sends tasks to a specific topic using a fixed partition to simulate a work queue, located in [`Main.kt:111-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L111-L126).

## Stats [#stats]

Real-time monitoring of sent messages, partition distribution, last offsets, and task counts, managed by the `Stats` class in [`Main.kt:131-165`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L131-L165).

## embeddedServer [#embeddedserver]

The HTTP interface providing health checks and JSON-serialized statistics via Ktor, initialized in [`Main.kt:58-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L58-L64).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                            | Lines   | What is there                                                                       |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| [`…/publisher/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/build.gradle.kts#L1-L7 "dev/publisher/build.gradle.kts")                                                                                        | `1-7`   | Dependencies for the client, Ktor server, and serialization.                        |
| [`…/publisher/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L35-L66 "dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt") | `35-66` | The `main` entry point that orchestrates the connection, producers, and the server. |

## Behaviour that Surprises [#behaviour-that-surprises]

* The `publishForever` function uses `topic.partitionFor(key)` to determine the partition before sending, which is a stable pure function of the key, unlike round-robin partitioners that might advance a counter (`Main.kt:97-101`).
* The `publishTasks` function explicitly uses `PartitionId(0)` to ensure all tasks go into a single partition, preventing the splitting that would occur if it used a keyed partitioner (`Main.kt:120-122`).
* The `openConnection` function uses an infinite `while(true)` loop to retry connection attempts until the broker is reachable, preventing startup race conditions (`Main.kt:78-84`).
