# The conformance executable (/wiki/booblik-native-conformance/the-conformance-executable)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

The conformance executable is a specialized Kotlin/Native binary designed to validate the Booblik protocol implementation by acting as a driver for specific verbs. Unlike standard clients, it is a fixture used by the conformance harness to ensure the broker adheres to the wire specification, specifically targeting edge cases in decoding and error reporting.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Conformance Harness
    participant C as Conformance Executable
    participant B as Booblik Broker

    H->>C: Execute with Verb (e.g., fetch)
    C->>B: Send Request (via BooblikConnection)
    B-->>C: Send Response (with potential truncation/errors)
    C->>C: Validate Response Logic
    alt Success
        C->>H: Exit 0 + stdout (key=value)
    else Broker Refusal
        C->>H: Exit 0 + stdout (error=CODE)
    else Client Failure
        C->>H: Exit > 0
    end"
/>

## The `conformance` and `probe` binaries [#the-conformance-and-probe-binaries]

The module is structured to produce two distinct executables to keep the library surface clean. As noted in [`build.gradle.kts:8-11`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/build.gradle.kts#L8-L11), a Kotlin/Native binary is linked from the target's own compilation; placing an entry point in the library would pollute the published klib and its ABI dump. Consequently, the module defines two separate binaries: the standard `conformance` client and a `probe` binary used for measurement ([`build.gradle.kts:26-31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/build.gradle.kts#L26-L31)).

## The `main` entry point and command verbs [#the-main-entry-point-and-command-verbs]

The lifecycle of a conformance run begins with a `capabilities` check, where the executable responds with its roles and name ([`Main.kt:33-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L33-L36)). If not checking capabilities, it retrieves the broker address from the `BOOBLIK_BROKER` environment variable ([`Main.kt:40-44`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L40-L44)) and dispatches one of the following verbs:

| Verb            | Description                                                                                                                                                                                                                                                    |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `metadata`      | Retrieves topic and partition information ([`Main.kt:48-50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L48-L50))  |
| `produce`       | Sends records with a specified `AckPolicy` ([`Main.kt:52-54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L52-L54)) |
| `produce-keyed` | Performs local partitioning before sending ([`Main.kt:56-58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L56-L58)) |
| `fetch`         | Requests records from a specific offset ([`Main.kt:60-62`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L60-L62))    |

## The `fetch` decoding and truncated tail edge cases [#the-fetch-decoding-and-truncated-tail-edge-cases]

The `fetch` implementation specifically tests the client's ability to handle partial data. It validates the `truncated` flag and `truncatedRecordBytes` when a record is larger than the `maxBytes` requested ([`Main.kt:156-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L156-L158)). This ensures the reader can distinguish between a state where they have caught up to the high watermark and a state where a record is simply too large to be fully read in one request.

## The `produce-keyed` partitioner exercise [#the-produce-keyed-partitioner-exercise]

Because the broker does not see the key in the wire protocol, the client must perform the partitioning logic itself to ensure the data lands in the correct location. The `produceKeyed` verb uses `topic.partitionFor(key)` to determine the destination partition before calling the produce command ([`Main.kt:128-130`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L128-L130)).

## The `report` error mechanism [#the-report-error-mechanism]

The executable distinguishes between a client-side failure and a broker-side refusal. A successful operation requires `ErrorCode.NONE` ([`Main.kt:77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L77)). If the broker returns an error, it is reported via `error=CODE` on stdout, but the process still exits with code 0 because the refusal is a valid protocol result ([`Main.kt:19-20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L19-L20)). A non-zero exit code is reserved for actual program failures.

## The `hex` payload encoding [#the-hex-payload-encoding]

To allow for raw data transmission via command line arguments, the client provides a helper to transform hex-encoded strings into `ByteArray` objects ([`Main.kt:170-171`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L170-L171)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                                |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ---------------------------------------------------------------------------- |
| [`booblik-native-conformance/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/build.gradle.kts#L18-L32 "booblik-native-conformance/build.gradle.kts")                                                                                               | `18-32` | Configuration for the Kotlin/Native targets and the two executable binaries. |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L27-L68 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt") | `27-68` | The `main` function and the command verb dispatch logic.                     |

## Behaviour that surprises [#behaviour-that-surprises]

* The `main` function uses `exitProcess(2)` for usage errors or missing environment variables, but returns a successful exit code even when the broker returns an `ErrorCode` (`booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt:30, 67`).
* The `produceKeyed` function performs the partitioning logic locally using `topic.partitionFor` because the broker is unaware of the key used for partitioning ([`Main.kt:128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L128)).
