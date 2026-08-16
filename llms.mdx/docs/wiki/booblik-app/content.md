# booblik-app (/wiki/booblik-app)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 5. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblik-app` module serves as the entry point for the Booblik broker. It manages the application lifecycle, from parsing configuration and initializing core components like the `Broker` and `BooblikServer` to managing background tasks for metrics and retention. It also provides a specialized health check utility to verify the broker's responsiveness.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start: Main.kt] --> B[Load BooblikConfig]
    B --> C[Initialize Broker & Server]
    C --> D[Start Background Coroutines]
    D --> E{Wait for Shutdown}
    E --> F[Shutdown Hook: Close Server & Broker]
    
    subgraph &#x22;Health Check&#x22;
    H[Health.kt] -->|METADATA Request| I[BooblikClient]
    I -->|Response| J{Success?}
    J -->|Yes| K[Exit 0]
    J -->|No/Timeout| L[Exit 1]
    end"
/>

## BooblikConfig [#booblikconfig]

Configuration is handled by the `BooblikConfig` data class, which ensures that the broker only boots if all parameters are valid. The loading logic follows a strict precedence: environment variables override properties files, which in turn override default values ([`BooblikConfig.kt:77-91`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L77-L91)).

The configuration parameters are parsed as follows:

| Parameter              | Type                  | Default / Source |
| ---------------------- | --------------------- | ---------------- |
| `booblik.data.dir`     | `Path`                | `"data"`         |
| `booblik.port`         | `Int`                 | `9092`           |
| `booblik.topics`       | `Map<TopicName, Int>` | `"default:1"`    |
| `booblik.segment.mode` | `SegmentMode`         | `MAPPED`         |
| `booblik.transport`    | `Transport`           | `SELECTOR`       |
| `booblik.fetch.mode`   | `FetchMode`           | `ZERO_COPY`      |

Validation is performed during initialization to prevent runtime failures ([`BooblikConfig.kt:49-55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L49-L55)).

More: [BooblikConfig](booblik-app/booblikconfig)

## Main execution lifecycle [#main-execution-lifecycle]

The startup sequence begins in `Main.kt`, where the configuration is loaded and the `Broker` and `BooblikServer` are initialized ([`Main.kt:34-73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L34-L73)). Once the server starts listening, the main thread enters a waiting state using a `CountDownLatch` ([`Main.kt:90-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L90-L103)).

A shutdown hook is registered to ensure a graceful exit: it closes the server first, then cancels background coroutines, and finally closes the broker to ensure all data batches reach the disk ([`Main.kt:92-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L92-L98)).

More: [Main execution lifecycle](booblik-app/main-execution-lifecycle)

## Metrics reporting and retention [#metrics-reporting-and-retention]

The application launches two primary background coroutines using a `SupervisorJob` to manage long-running tasks ([`Main.kt:84-86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L84-L86)).

* **Metrics Reporting**: The `reportMetrics` function calculates rates by comparing snapshots of the `Metrics` object over a specified interval ([`Main.kt:113-125`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L113-L125)).
* **Retention Policy**: The `applyRetention` function periodically triggers the broker to remove segments based on configured time or size limits ([`Main.kt:135-144`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L135-L144)).

More: [Metrics reporting and retention](booblik-app/metrics-reporting-and)

## Health check mechanism [#health-check-mechanism]

The `Health` object provides a standalone utility to verify that the broker is not just accepting TCP connections, but is actually capable of processing requests ([`Health.kt:20-21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L20-L21)). It performs a `METADATA` request via a `BooblikClient`.

To prevent the health check from hanging if the broker is unresponsive, the check uses a worker thread with a join timeout ([`Health.kt:66-73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L66-L73)). A successful check requires a `MetadataResult` with an `ErrorCode.NONE` ([`Health.kt:81-84`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L81-L84)).

## Build and distribution [#build-and-distribution]

The module is configured via `build.gradle.kts` as a Kotlin JVM application ([`build.gradle.kts:1-11`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/build.gradle.kts#L1-L11)). It uses the `application` plugin to define the main entry point and customizes the distribution to include a specific health check script ([`build.gradle.kts:29-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/build.gradle.kts#L29-L46)).

The application's runtime behavior is influenced by JVM arguments, which are explicitly printed at startup to ensure the running profile matches the intended one ([`Main.kt:44-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L44-L45)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                         | Lines    | What is there                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------- |
| [`booblik-app/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/build.gradle.kts#L13-L19 "booblik-app/build.gradle.kts")                                                                                       | `13-19`  | Application main class and JVM argument configuration |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L32-L48 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt") | `32-48`  | Data class definition and configuration properties    |
| [`…/app/Health.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L32-L86 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt")                      | `32-86`  | Health check logic and client interaction             |
| [`…/app/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L33-L104 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt")                           | `33-104` | Main entry point and shutdown hook implementation     |

## Behaviour that surprise [#behaviour-that-surprise]

* `BooblikConfig.load` will throw an exception if a configuration file is explicitly provided but cannot be found, rather than silently using defaults ([`BooblikConfig.kt:82-86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L82-L86)).
* The `Health` check uses a separate thread to bound the request time, because a blocking read on a `SocketChannel` does not respond to `SO_TIMEOUT` ([`Health.kt:47-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L47-L49)).
* `Main.kt` uses a `CountDownLatch` to keep the main thread alive, ensuring the process doesn't exit until the shutdown hook completes ([`Main.kt:90-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L90-L103)).
