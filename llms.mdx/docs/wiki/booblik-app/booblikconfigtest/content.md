# BooblikConfigTest (/wiki/booblik-app/booblikconfigtest)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `f508a4b65b3f`, 2026-08-15, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Environment Variables] -->|Overrides| B[Properties File]
    B -->|Overrides| C[Hardcoded Defaults]
    C --> D[BooblikConfig]
    D --> E{Validation}
    E -->|Success| F[Broker Starts]
    E -->|Failure| G[IllegalStateException/IllegalArgumentException]"
/>

## BooblikConfig loading precedence [#booblikconfig-loading-precedence]

The configuration follows a strict hierarchy where environment variables take the highest precedence, followed by properties defined in a file, and finally falling back to hardcoded defaults if no value is provided (`BooblikConfig.kt:79-91`). For example, an environment variable like `BOOBLIK_PORT` will override a `booblik.port` setting found in a properties file (`BooblikConfigTest.kt:46-47`).

## TopicName and partition count parsing [#topicname-and-partition-count-parsing]

The configuration parses a comma-separated string of topics into a map where each `TopicName` is associated with an integer representing its partition count (`BooblikConfig.kt:144-154`). The format required is `name:partitions` (e.g., `orders:3, clicks:1`), and the parser ensures that each topic has at least one partition (`BooblikConfig.kt:152`).

## Validation of SegmentMode and FetchMode [#validation-of-segmentmode-and-fetchmode]

Enum-based settings are strictly validated against their allowed values during the loading process (`BooblikConfig.kt:106-114`). The following table summarizes the configuration parameters:

| Parameter              | Type          | Default / Example |
| ---------------------- | ------------- | ----------------- |
| `booblik.segment.mode` | `SegmentMode` | `MAPPED`          |
| `booblik.fetch.mode`   | `FetchMode`   | `ZERO_COPY`       |
| `booblik.transport`    | `Transport`   | `SELECTOR`        |

## Failure modes and startup refusal [#failure-modes-and-startup-refusal]

The broker is designed to refuse to boot if configuration errors are detected, preventing "surprises at three in the morning" (`BooblikConfigTest.kt:16`). Specific failure modes include:

* **Type Mismatches:** Providing a non-integer value for a numeric field (e.g., `booblik.port=nine-thousand`) results in an `IllegalStateException` (`BooblikConfigTest.kt:67`).
* **Invalid Ranges:** Port numbers must be within `0..65535` (`BooblikConfig.kt:50`).
* **Logical Errors:** A segment capacity of zero or an empty topic list will trigger an `IllegalArgumentException` (`BooblikConfigTest.kt:74-78`).

## FlushPolicy and retention configuration [#flushpolicy-and-retention-configuration]

The configuration supports complex policies for data durability and lifecycle management (`BooblikConfig.kt:130-136`):

| Configuration Key             | Type    | Description                                     |
| ----------------------------- | ------- | ----------------------------------------------- |
| `booblik.flush.every.records` | `Long`  | Number of records before a flush                |
| `booblik.flush.every.millis`  | `Long`  | Time interval before a flush                    |
| `booblik.retention.bytes`     | `Long?` | Maximum size in bytes before retention kicks in |
| `booblik.retention.millis`    | `Long?` | Maximum age in millis before retention kicks in |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                     | Lines    | What is there                                                                |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------------------------------------------------------------------------- |
| [`…/app/BooblikConfigTest.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt#L18-L31 "booblik-app/src/test/kotlin/ru/workinprogress/booblik/app/BooblikConfigTest.kt") | `18-31`  | A helper function `withFile` for testing configuration with temporary files. |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L32-L48 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt")             | `32-48`  | The `BooblikConfig` data class definition and its `init` validation block.   |
| [`…/app/BooblikConfig.kt`](https://github.com/youndie/booblik/blob/f508a4b65b3f92859af222822bf3394f4a7dc534/booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt#L68-L141 "booblik-app/src/main/kotlin/ru/workinprogress/booblik/app/BooblikConfig.kt")            | `68-141` | The `companion object` containing the `load` logic and parsing helpers.      |

## Behaviour that does surprise [#behaviour-that-does-surprise]

* The `load` function in `BooblikConfig.kt` will throw an error if a file path is provided but the file does not exist, whereas it is perfectly fine to load with no file at all (`BooblikConfig.kt:82-85`).
* The `SegmentMode.MAPPED` value is the default, but the configuration allows for other modes via the `enum` helper (`BooblikConfig.kt:122-126`).
* The `BooblikConfig` class uses an `init` block to perform secondary validation on values like `port` and `segmentCapacity` after they have been parsed (`BooblikConfig.kt:49-55`).
