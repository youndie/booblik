# The conformance verb interface (/wiki/booblik-native-conformance/the-conformance-verb)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The conformance verb interface provides a minimal, language-neutral command-line contract used to drive clients under test. It allows a harness to verify that a client implementation correctly implements the Booblik protocol by communicating via `stdin`/`stdout` and environment variables.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness (Python)
    participant C as Client (Native/Python)
    participant B as Broker

    H->>C: Invoke with <verb> [args]
    Note over C: Set BOOBLIK_BROKER env
    C->>B: Protocol Request (e.g., Produce/Fetch)
    alt Success
        B-->>C: Protocol Response
        C->>H: stdout: key=value
        H->>H: Parse results
    else Broker Refusal
        B-->>C: ErrorCode (e.g., UNKNOWN_TOPIC)
        C->>H: stdout: error=CODE
    else Client Failure
        C-->>H: Exit non-zero + stderr
    end"
/>

## The `capabilities` verb [#the-capabilities-verb]

The handshake begins with the `capabilities` verb, which allows the client to declare its supported roles and its identity to the harness. As seen in [`Main.kt:33-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L33-L36), the client must output `roles=producer,consumer` and a `name` (e.g., `kotlin-native` or `python`). This allows the harness to determine which subsequent tests (like `fetch`) are valid for the specific client implementation.

## The `metadata` verb [#the-metadata-verb]

The `metadata` verb is used to retrieve the current state of a topic. The client must report the status of each partition, specifically the partition ID, the `logStartOffset`, and the `highWatermark` ([`Main.kt:79-84`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L79-L84)). This is used to verify that the client can correctly interpret the broker's view of the log boundaries.

## The `produce` and `produce-keyed` verbs [#the-produce-and-produce-keyed-verbs]

These verbs handle the submission of records. The `produce` verb supports different `AckPolicy` modes, which are mapped as follows:

| AckPolicy | Description                                                                                                                                                                                                                                                       |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `none`    | No response expected; the client returns immediately ([`Main.kt:98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L98)) |
| `written` | Waits for the broker to acknowledge the write                                                                                                                                                                                                                     |
| `forced`  | Waits for the broker to flush to disk                                                                                                                                                                                                                             |

The `produce-keyed` verb specifically exercises the client-side partitioner. The client must use its own logic to determine the partition from the key via `partitionFor` before sending the request to the broker, as the broker does not see the key ([`Main.kt:128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L128)).

## The `fetch` verb and truncated records [#the-fetch-verb-and-truncated-records]

The `fetch` verb tests the client's ability to consume records and handle edge cases in the response stream. The client must report the `highWatermark` and the hex-encoded records ([`Main.kt:152-163`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L152-L163)). A critical edge case involves truncated records: if a response contains no complete records but the `truncated` flag is set, the client must report `recordExceedsMaxBytes` to indicate that the next record is larger than the requested `maxBytes` ([`Main.kt:156-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L156-L158)).

## Exit codes and `ErrorCode` reporting [#exit-codes-and-errorcode-reporting]

The interface maintains a strict distinction between client-side failures and broker-side refusals:

* **Exit 0**: The verb was carried out successfully. This includes cases where the broker refused the request (e.g., `error=UNKNOWN_TOPIC_OR_PARTITION`), which is considered a valid result of the operation ([`client.py:18-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L18-L19)).
* **Non-zero Exit**: The client itself encountered a failure (e.g., a crash or invalid arguments), which is considered an unexpected outcome by the harness ([`client.py:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L19)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                              | Lines   | What is there                                                                       |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt#L27-L68 "booblik-native-conformance/src/nativeMain/kotlin/ru/workinprogress/booblik/native/conformance/Main.kt") | `27-68` | The `main` entry point and verb dispatching logic for the Kotlin/Native client.     |
| [`…/harness/client.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L51-L94 "conformance/harness/client.py")                                                                                                                                                   | `51-94` | The Python `Client` class that executes subprocesses and parses `key=value` stdout. |
| [`…/python/conformance.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/conformance.py#L20-L53 "clients/python/conformance.py")                                                                                                                                               | `20-53` | The Python implementation of the conformance client.                                |

## Behaviour that surprises [#behaviour-that-surprises]

* **Open-loop measurement**: In `LoadDriver.kt:162-164`, latency is measured from the moment a request was *due* according to a fixed schedule, rather than when it was actually sent. This prevents "coordinated omission" where a slow broker hides its own latency by slowing down the client's request rate.
* **Spin-tailing**: To achieve high precision without burning CPU, `parkNanos` in `LoadDriver.kt:234-240` uses a hybrid approach: it `parkNanos` for the bulk of the wait and then uses `Thread.onSpinWait()` for the final 50 microseconds.
