# The conformance client contract (/wiki/booblik-conformance/the-conformance-client)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness (Python)
    participant C as Client (CLI)
    participant B as Broker

    H->>C: Invoke with Verb + Args
    Note over C: Environment: BOOBLIK_BROKER
    C->>B: Network Request
    alt Success
        B-->>C: Protocol Response
        C->>H: stdout (key=value)
    else Protocol Error
        B-->>C: Error Code
        C->>H: stdout (error=CODE)
    else Client Failure
        C-->>H: Exit Code != 0 (stderr)
    end"
/>

## The CLI Verb Interface [#the-cli-verb-interface]

The client is invoked using a specific command-line structure where the first argument determines the action performed. As defined in [`client.py:25-29`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L25-L29), the mapping of verbs to actions is as follows:

| Verb            | Arguments                                  | Expected Answers                                  |
| --------------- | ------------------------------------------ | ------------------------------------------------- |
| `capabilities`  | —                                          | `roles=...`, `name=...`                           |
| `metadata`      | `<topic>`                                  | `partition=<id> <logStartOffset> <highWatermark>` |
| `produce`       | `<topic> <partition> <ack> <hex>[,<hex>…]` | `baseOffset=`, `logEndOffset=`                    |
| `produce-keyed` | `<topic> <keyHex> <payloadHex>`            | `partition=`, `baseOffset=`                       |
| `fetch`         | `<topic> <partition> <offset> <maxBytes>`  | `highWatermark=`, `record=<hex>`                  |

## The `capabilities` Declaration [#the-capabilities-declaration]

Before testing logic, the harness calls the `capabilities` verb to understand what the client is capable of. According to [`Main.kt:43-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L43-L46), the client must declare its roles (such as `producer` or `consumer`) and its name. The harness uses this to decide whether to skip certain checks, such as `fetch` for a producer-only client ([`client.py:39`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L39)).

## The `produce` and `produce-keyed` Mechanics [#the-produce-and-produce-keyed-mechanics]

There is a critical distinction between standard production and keyed production:

* **Standard `produce`**: The caller specifies the partition directly. If `ack=none` is used, the client must return immediately without reading a response ([`Main.kt:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L124-L126)).
* **`produce-keyed`**: The client is responsible for selecting the partition. The client must first fetch metadata to discover available partitions and then use a partitioner to select one based on the key ([`Main.kt:154-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L154-L158)).

## The `fetch` and `truncated` Edge Case [#the-fetch-and-truncated-edge-case]

When fetching data, the client must handle cases where the response is incomplete. If a record is larger than the `maxBytes` requested, the broker may return a truncated response. In this scenario, the client must report the `truncatedRecordBytes` and the `highWatermark` ([`Main.kt:182-183`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L182-L183)). This prevents the caller from misinterpreting a partial record as the end of the log.

## Exit Codes and Error Reporting [#exit-codes-and-error-reporting]

The contract distinguishes between protocol-level errors and client-level failures:

* **Protocol Errors**: If the broker refuses a request (e.g., `UNKNOWN_TOPIC_OR_PARTITION`), the client must report this via `stdout` using the format `error=CODE` and exit with code `0` ([`Main.kt:96`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L96)).
* **Client Failures**: Any exception that is not a protocol error (such as a network failure or a crash) results in a non-zero exit code and a diagnostic message on `stderr` ([`Main.kt:85-86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L85-L86)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                      | Lines   | What is there                                                   |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------- |
| [`…/conformance/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L37-L88 "booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt") | `37-88` | The `main` entry point and verb dispatching logic.              |
| [`…/harness/client.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L51-L98 "conformance/harness/client.py")                                                                                                           | `51-98` | The Python `Client` class used by the harness to drive the CLI. |

## Behaviour that surprises [#behaviour-that-surprises]

* **Immediate Return on `none`**: In the `produce` function, if the `AckPolicy` is `NONE`, the client must not attempt to read a response from the socket, as the broker will not send one ([`Main.kt:124-126`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L124-L126)).
* **Metadata-Driven Partitioning**: In `produceKeyed`, the client must not rely on a hardcoded partition count; it must fetch the actual partition list from the broker via `sendMetadata` to ensure the `Partitioner.Fnv1a` logic matches the broker's view ([`Main.kt:149-157`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt#L149-L157)).
