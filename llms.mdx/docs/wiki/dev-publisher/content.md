# dev/publisher (/wiki/dev-publisher)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    Config[PublisherConfig] -->|Environment| Main[Main.kt]
    Main -->|Connection| Producer[Producer]
    Producer -->|Events| Topic[Events Topic]
    Producer -->|Tasks| TaskTopic[Tasks Topic]
    Main -->|HTTP| Ktor[Ktor Server]
    Ktor -->|Stats| Stats[Stats]"
/>

## PublisherConfig [#publisherconfig]

Configuration parameters loaded from environment variables for broker connection, topic names, and timing intervals, as defined in [`Main.kt:177-200`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L177-L200).

| Variable             | Environment Variable      | Default Value |
| -------------------- | ------------------------- | ------------- |
| `brokerHost`         | `BOOBLIK_HOST`            | `"127.0.0.1"` |
| `brokerPort`         | `BOOBLIK_PORT`            | `9092`        |
| `topic`              | `BOOBLIK_TOPIC`           | `"events"`    |
| `intervalMillis`     | `PUBLISH_INTERVAL_MILLIS` | `1000`        |
| `users`              | `PUBLISH_USERS`           | `9`           |
| `httpPort`           | `HTTP_PORT`               | `8080`        |
| `tasksTopic`         | `BOOBLIK_TASKS_TOPIC`     | `null`        |
| `taskIntervalMillis` | `TASK_INTERVAL_MILLIS`    | `700`         |

## openConnection [#openconnection]

The retry mechanism used to establish a `BooblikConnection`, ensuring the publisher waits for the broker to become available by looping and delaying for 1000ms upon failure, as seen in [`Main.kt:76-86`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L76-L86).

More: [openConnection](dev-publisher/openconnection)

## publishForever [#publishforever]

The main event loop that simulates user activity by hashing user keys to specific partitions and sending JSON payloads, which includes recording stats for each sent record in [`Main.kt:88-108`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L88-L108).

## publishTasks [#publishtasks]

A secondary loop that sends tasks to a specific topic using a fixed partition (`PartitionId(0)`) to simulate a work queue, as implemented in [`Main.kt:111-126`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L111-L126).

## Stats [#stats]

An internal monitoring mechanism that tracks sent messages, partition distribution, last offsets, and task counts via the `Stats` class in [`Main.kt:131-165`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L131-L165).

## embeddedServer [#embeddedserver]

The Ktor-based HTTP interface providing health checks and real-time statistics via JSON, which is started within the `runBlocking` block in [`Main.kt:58-64`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L58-L64).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                              | Lines     | What is there                                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | --------------------------------------------------------------------------- |
| [`…/publisher/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/build.gradle.kts#L1-L7 "dev/publisher/build.gradle.kts")                                                                                          | `1-7`     | Dependencies for the Ktor server, Booblik client, and serialization.        |
| [`…/publisher/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L1-L66 "dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt")    | `1-66`    | The `main` function that orchestrates the connection, producer, and server. |
| [`…/publisher/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L177-L200 "dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt") | `177-200` | The `PublisherConfig` data class and its environment-based factory.         |

## Behaviour that surprises [#behaviour-that-surprises]

* The `topic.partitionFor(key)` call in `publishForever` is used to determine the partition before sending, because the keyed partitioner is a pure function of the key, whereas a round-robin partitioner would consume a slot and cause records to skip partitions ([`Main.kt:97-101`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L97-L101)).
* The `publishTasks` function explicitly uses `PartitionId(0)` to ensure all tasks go into a single partition to allow any worker to take any task, rather than splitting them by partition ([`Main.kt:117-121`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/dev/publisher/src/main/kotlin/ru/workinprogress/booblik/dev/publisher/Main.kt#L117-L121)).
