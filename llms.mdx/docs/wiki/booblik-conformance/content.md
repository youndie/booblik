# booblik-conformance (/wiki/booblik-conformance)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant Harness
    participant ConformanceClient
    participant Broker

    Harness->>ConformanceClient: Execute command (verb)
    ConformanceClient->>Broker: Send Protocol Request
    alt AckPolicy.NONE
        Broker-->>ConformanceClient: (No response)
        ConformanceClient-->>Harness: Exit/Return
    else AckPolicy.WRITTEN / FORCED
        Broker-->>ConformanceClient: Protocol Response
        ConformanceClient-->>Harness: Print results/errors
    end"
/>

## The conformance client contract [#the-conformance-client-contract]

This module serves as the reference implementation of the conformance client contract, acting as a fixture for the harness to ensure the protocol is correctly implemented [`Main.kt:14-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L14-L19). It is designed to prove that the harness works by providing a "green" baseline where every check in the harness passes before any other client is tested [`Main.kt:18-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L18-L19).

More: [The conformance client contract](booblik-conformance/the-conformance-client)

## Command-line verbs [#command-line-verbs]

The operational modes available via the command line are:

| Verb            | Description                                                                                                                                                                                                                                             |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `metadata`      | Retrieves topic metadata including partition offsets [`Main.kt:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L60)             |
| `produce`       | Produces a list of records to a specific partition [`Main.kt:64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L64)               |
| `produce-keyed` | Partitions a payload based on a key using Fnv1a before producing [`Main.kt:68`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L68) |
| `fetch`         | Fetches records from a partition up to a specific byte limit [`Main.kt:72`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L72)     |

More: [Command-line verbs](booblik-conformance/command-line-verbs)

## Produce and AckPolicy [#produce-and-ackpolicy]

The interaction between produce requests and the response lifecycle is governed by the `AckPolicy` [`Main.kt:116-121`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L116-L121):

| AckPolicy | Behavior                                                                                                                                                                                                                                                  |
| --------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `none`    | No response is expected; the client returns immediately [`Main.kt:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L124-L126) |
| `written` | Client waits for and reads the produce response [`Main.kt:160`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L160)                  |
| `forced`  | Client waits for and reads the produce response [`Main.kt:160`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L160)                  |

## Partitioner.Fnv1a [#partitionerfnv1a]

For keyed production, the client uses `Partitioner.Fnv1a` to select a partition [`Main.kt:158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L158). This selection is made by mapping the key against the list of partitions obtained from the broker's metadata [`Main.kt:154-157`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L154-L157).

## Fetch truncation and maxBytes [#fetch-truncation-and-maxbytes]

When performing a `fetch`, the client handles cases where the response might be incomplete [`Main.kt:182`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182). If the `records` list is empty but the `truncated` flag is set, it indicates that the next record in the log is larger than the `maxBytes` requested, resulting in a `recordExceedsMaxBytes` report [`Main.kt:182-184`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182-L184).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                        | Lines     | What is there                                      |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | -------------------------------------------------- |
| [`booblik-conformance/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/build.gradle.kts#L18-L20 "booblik-conformance/build.gradle.kts")                                                                              | `18-20`   | Dependency declaration for `:booblik-client`       |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L37-L88 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt")   | `37-88`   | Main entry point and command-line argument parsing |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L90-L106 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt")  | `90-106`  | Metadata retrieval logic                           |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L108-L132 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `108-132` | Standard produce logic                             |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L143-L166 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `143-166` | Keyed produce logic using Fnv1a                    |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L168-L189 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `168-189` | Fetch logic and truncation handling                |

## Behaviour that surprises [#behaviour-that-surprises]

* When using `AckPolicy.NONE`, the `BooblikClient.sendProduce` function returns `null`, and the client must return immediately without reading to avoid blocking forever [`Main.kt:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L124-L126).
* A `fetch` response that contains an empty list of records but has the `truncated` flag set is not a sign of having caught up to the head, but rather an indication that the next record exceeds `maxBytes` [`Main.kt:182-184`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182-L184).
