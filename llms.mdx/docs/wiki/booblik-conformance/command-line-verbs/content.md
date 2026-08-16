# Command-line verbs (/wiki/booblik-conformance/command-line-verbs)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness
    participant C as Conformance Client
    participant B as Booblik Broker

    H->>C: Invoke Verb (e.g., produce)
    C->>B: Send Request (via BooblikClient)
    alt AckPolicy.NONE
        C-->>H: Exit 0 (No response read)
    else AckPolicy.WRITTEN / FORCED
        B-->>C: Send Response
        C-->>H: Print key=value (stdout)
    end
    alt Broker Refusal
        B-->>C: ErrorCode
        C-->>H: Print error=CODE (stdout)
    else Client Failure
        C-->>H: Exit 1 (stderr)
    end"
/>

## The `capabilities` verb [#the-capabilities-verb]

The handshake mechanism where the client declares its roles (producer, consumer) and name. The client must respond with `roles=` and `name=` to allow the harness to determine which checks to run [`Main.kt:43-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L43-L46).

## The `metadata` verb [#the-metadata-verb]

Retrieving topic information, including partition IDs, log start offsets, and high watermarks. The client iterates through the topics and partitions returned by the broker to print `partition=<id> <start_offset> <high_watermark>` [`Main.kt:98-104`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L98-L104).

## The `produce` verb and `AckPolicy` [#the-produce-verb-and-ackpolicy]

Mechanics of sending records and the behavior of `AckPolicy.NONE` (silent requests) versus `WRITTEN` and `FORCED`. The `ack` argument is mapped to an `AckPolicy` as follows:

| Argument  | `AckPolicy`         | Behavior                                                                                                                                                                                                                                         |
| --------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `none`    | `AckPolicy.NONE`    | No response is expected; the client returns immediately [`Main.kt:117`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L117) |
| `written` | `AckPolicy.WRITTEN` | Client waits for a response [`Main.kt:118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L118)                             |
| `forced`  | `AckPolicy.FORCED`  | Client waits for a response [`Main.kt:119`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L119)                             |

## The `produce-keyed` verb and `Partitioner.Fnv1a` [#the-produce-keyed-verb-and-partitionerfnv1a]

How the client-side partitioner selects a partition based on a key before the broker sees the data. The client first fetches metadata to find available partitions and then uses `Partitioner.Fnv1a.partitionFor` to select the target partition [`Main.kt:158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L158).

## The `fetch` verb and `maxBytes` truncation [#the-fetch-verb-and-maxbytes-truncation]

Retrieving records, handling the high watermark, and the edge case where a record exceeds `maxBytes` resulting in a truncated tail. If a response contains no complete records but the `truncated` flag is set, the client reports the `truncatedRecordBytes` [`Main.kt:182-184`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182-L184).

## Exit codes and `ErrorCode` reporting [#exit-codes-and-errorcode-reporting]

Distinguishing between client-side failures (non-zero exit) and broker-side refusals (exit zero with `error=CODE`). A broker refusal is considered a valid result and is reported via `error=CODE` on stdout [`Main.kt:84-85`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L84-L85), whereas a client-side exception results in a non-zero exit code [`Main.kt:86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L86).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                        | Lines     | What is there                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------- |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L37-L88 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt")   | `37-88`   | The main entry point and verb dispatching logic |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L108-L132 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `108-132` | The `produce` verb implementation               |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L143-L166 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `143-166` | The `produceKeyed` verb implementation          |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L168-L189 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `168-189` | The `fetch` verb implementation                 |

## Behaviour that surprise [#behaviour-that-surprise]

* `BooblikClient.sendProduce` returns `null` when `AckPolicy.NONE` is used, which is a signal that no response should be read from the socket [`BooblikClient.kt:48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/BooblikClient.kt#L48).
* A `fetch` request at the high watermark is not an error; it returns an empty list of records and the current high watermark [`ServerTest.kt:97-100`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L97-L100).
* `produce-keyed` requires the client to perform a `metadata` request first because the broker does not receive the key to perform partitioning itself [`Main.kt:136-150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L136-L150).
