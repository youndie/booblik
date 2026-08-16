# BooblikConfig (/wiki/booblik-app/booblikconfig)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Environment Variables] -->|Overrides| B[Properties File]
    B -->|Overrides| C[Default Values]
    C --> D[BooblikConfig]
    D -->|Validation| E{Startup Success?}
    E -->|No| F[Refusal to Boot]
    E -->|Yes| G[Broker Start]"
/>

## BooblikConfig [#booblikconfig]

The data class representing the validated broker configuration, including topic partitioning and retention policies. It encapsulates all settings required for the broker to initialize, such as the data directory, port, and transport settings [`BooblikConfig.kt:32-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L32-L48).

## Configuration Precedence [#configuration-precedence]

The hierarchy of settings where environment variables override properties files, which in turn override defaults. The `raw` function implements this logic by checking the environment first, then the properties, and finally falling back to a default or erroring out [`BooblikConfig.kt:90-91`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L90-L91).

The mapping for environment variables follows a specific convention:

| Key                | Environment Variable |
| ------------------ | -------------------- |
| `booblik.port`     | `BOOBLIK_PORT`       |
| `booblik.data.dir` | `BOOBLIK_DATA_DIR`   |
| `booblik.topics`   | `BOOBLIK_TOPICS`     |

## Topic Parsing [#topic-parsing]

The mechanics of parsing the `booblik.topics` string into a map of `TopicName` to partition counts. The `parseTopics` function splits the input string by commas and then by colons to extract the name and the number of partitions [`BooblikConfig.kt:144-154`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L144-L154).

The expected format is:

| Format            | Example             |
| ----------------- | ------------------- |
| `name:partitions` | `orders:3,clicks:1` |

## Startup Validation [#startup-validation]

The `init` block requirements and error handling for invalid ports, empty topics, or non-positive capacity/interval values. The configuration is strictly validated during construction to ensure that a typo results in a refusal to boot rather than a runtime surprise [`BooblikConfig.kt:49-55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L49-L55).

The following constraints are enforced:

| Parameter              | Requirement               |
| ---------------------- | ------------------------- |
| `port`                 | Must be in range 0..65535 |
| `topics`               | Must not be empty         |
| `segmentCapacity`      | Must be greater than 0    |
| `indexIntervalBytes`   | Must be greater than 0    |
| `retentionCheckMillis` | Must be greater than 0    |

## BooblikConfigTest [#booblikconfigtest]

Verification of default values, environment overrides, and the refusal to boot on nonsense values or missing files. The test suite ensures that the broker fails fast when provided with invalid configuration, such as non-integer ports or invalid segment modes [`BooblikConfigTest.kt:63-79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L63-L79).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                     | Lines    | What is there                                                           |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------------------------- |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L32-L48 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt")             | `32-48`  | The `BooblikConfig` data class definition and its properties.           |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L68-L141 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt")            | `68-141` | The `companion object` containing the `load` logic and parsing helpers. |
| [`…/app/BooblikConfigTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L34-L80 "booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt") | `34-80`  | Unit tests for defaults, precedence, and validation logic.              |

## Behaviour that surprises [#behaviour-that-surprises]

* The `load` function in `BooblikConfig.kt:76-141` will throw an `IllegalArgumentException` if a configuration file is explicitly provided but does not exist on the filesystem [`BooblikConfig.kt:82-85`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L82-L85).
* The `parseTopics` function in `BooblikConfig.kt:144-154` requires that every topic entry contains exactly one colon separating the name and the partition count [`BooblikConfig.kt:149-150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L149-L150).
