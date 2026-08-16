# booblik-app (/wiki/booblik-app)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 5. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Start: Main.kt] --> B[Load BooblikConfig]
    B --> C[Open Broker]
    C --> D[Start BooblikServer]
    D --> E[Launch Background Coroutines]
    E --> F[Wait for Shutdown Hook]
    F --> G[Close Server & Broker]
    
    H[Health.kt] -->|METADATA Request| D"
/>

## BooblikConfig [#booblikconfig]

Configuration loading and validation logic. The `BooblikConfig` data class [`BooblikConfig.kt:32-48`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L32-L48) holds all settings required for the broker to start. It implements a precedence rule where environment variables override properties files, which in turn override default values, as seen in the `load` function [`BooblikConfig.kt:77-91`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L77-L91).

The configuration parameters are parsed as follows:

| Parameter              | Type                  | Default / Source |
| ---------------------- | --------------------- | ---------------- |
| `booblik.data.dir`     | `Path`                | `"data"`         |
| `booblik.port`         | `Int`                 | `9092`           |
| `booblik.topics`       | `Map<TopicName, Int>` | `"default:1"`    |
| `booblik.segment.mode` | `SegmentMode`         | `MAPPED`         |
| `booblik.transport`    | `Transport`           | `SELECTOR`       |
| `booblik.fetch.mode`   | `FetchMode`           | `ZERO_COPY`      |

Validation is performed in the `init` block [`BooblikConfig.kt:49-55`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L49-L55) to ensure values like `port` and `segmentCapacity` are within valid ranges.

## BooblikConfigTest [#booblikconfigtest]

Validation of configuration precedence and error handling. The test suite [`BooblikConfigTest.kt:17-109`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L17-L109) ensures that the configuration logic is robust. It specifically verifies that:

* Defaults are applied when no configuration is provided [`BooblikConfigTest.kt:34-41`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L34-L41).
* Environment variables take precedence over file settings [`BooblikConfigTest.kt:44-50`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L44-L50).
* Invalid values (like non-integer ports) cause the application to fail fast rather than using silent defaults [`BooblikConfigTest.kt:63-79`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L63-L79).

More: [BooblikConfigTest](booblik-app/booblikconfigtest)

## Main [#main]

The broker lifecycle, including startup, metrics reporting, and shutdown hooks. The `main` function in [`Main.kt:33-104`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L33-L104) orchestrates the entire process. It initializes the `Broker` and `BooblikServer`, then launches two background coroutines for reporting metrics [`Main.kt:85`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L85) and applying retention policies [`Main.kt:86`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L86). A `ShutdownHook` is registered to ensure the server and broker are closed in the correct order to prevent data loss [`Main.kt:91-100`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L91-L100).

## Health [#health]

The METADATA-based health check mechanism. The `Health` object in [`Health.kt:32-86`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L32-L86) provides a way to verify the broker is actually serving requests. Instead of a simple TCP connection, it performs a `sendMetadata` and `receiveMetadata` call [`Health.kt:59-61`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L59-L61). This ensures the session loop and registry are functional. The check is designed to exit with code 0 if the broker responds correctly and code 1 if it fails or times out [`Health.kt:70-83`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L70-L83).

## application distribution [#application-distribution]

The build configuration and the health check start script. The `build.gradle.kts` file [`build.gradle.kts:29-46`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/build.gradle.kts#L29-L46) defines a custom task `healthStartScripts` to create a specific entry point for the health check. This script is included in the main distribution under the `bin` directory [`build.gradle.kts:44`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/build.gradle.kts#L44).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                     | Lines    | What is there                                                                        |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------ |
| [`booblik-app/build.gradle.kts`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/build.gradle.kts#L29-L46 "booblik-app/build.gradle.kts")                                                                                                   | `29-46`  | Registration of the `healthStartScripts` task and its inclusion in the distribution. |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L49-L55 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt")             | `49-55`  | The `init` block containing validation requirements for configuration.               |
| [`…/app/Health.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L59-L62 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt")                                  | `59-62`  | The logic for sending and receiving metadata during a health check.                  |
| [`…/app/Main.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L91-L100 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt")                                       | `91-100` | The shutdown hook implementation for graceful termination.                           |
| [`…/app/BooblikConfigTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L63-L79 "booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt") | `63-79`  | Test cases for ensuring invalid configuration values trigger errors.                 |

## Behaviour that surprising [#behaviour-that-surprising]

* `BooblikConfig.load` will throw an `IllegalStateException` if a configuration value is provided but is of the wrong type, rather than falling back to a default [`BooblikConfigTest.kt:67`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L67).
* The `Health` check uses a separate thread with a `join` timeout to ensure that a hung broker does not cause the health check itself to hang indefinitely [`Health.kt:66-73`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Health.kt#L66-L73).
* The `Main.kt` shutdown hook closes the `server` before the `broker` to ensure that any in-flight batches are written to disk before the log is closed [`Main.kt:94-98`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/Main.kt#L94-L98).
