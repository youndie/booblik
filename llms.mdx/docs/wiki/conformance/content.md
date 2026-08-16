# conformance (/wiki/conformance)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `conformance` module provides a suite of tools to ensure that any implementation of the booblik protocol—regardless of the programming language—adheres strictly to the specification. It focuses on catching "silent" failures: bugs that allow a client to run and appear functional while actually losing data, miscalculating partitions, or failing to handle specific edge cases like truncated network tails.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness (run.py)
    participant C as Client (Under Test)
    participant B as Broker (Live)
    participant W as Wire (wire.py)

    H->>C: Invoke Verb (e.g., produce-keyed)
    C->>B: Send Request (via TCP)
    B-->>C: Send Response
    C->>H: Print key=value to stdout
    H->>W: Connect to Broker
    W->>B: Fetch records (independent check)
    H->>H: Assert Client matches Wire/Vectors"
/>

## The conformance harness contract [#the-conformance-harness-contract]

The harness interacts with the client via a command-line interface where the client is invoked as `<command> <verb> [args...]` ([`client.py:28`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L28)). The interaction is based on a specific set of verbs and expected answers:

| Verb            | Arguments                                  | Answers                                               |
| --------------- | ------------------------------------------ | ----------------------------------------------------- |
| `capabilities`  | —                                          | `roles=producer[,consumer]`, `name=<what to call it>` |
| `metadata`      | `<topic>`                                  | `partition=<id> <logStartOffset> <highWatermark>`     |
| `produce`       | `<topic> <partition> <ack> <hex>[,<hex>…]` | `baseOffset=`, `logEndOffset=`                        |
| `produce-keyed` | `<topic> <keyHex> <payloadHex>`            | `partition=`, `baseOffset=`                           |
| `fetch`         | `<topic> <partition> <offset> <maxBytes>`  | `highWatermark=`, `record=<hex>`                      |

The client must write `key=value` lines to stdout, and any non-zero exit code from the client is treated as a failure ([`client.py:81-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L81-L83)).

## The partitioner and checksum algorithms [#the-partitioner-and-checksum-algorithms]

The module implements two critical algorithms that are never sent over the wire but must be identical across all clients to ensure data consistency. These are defined in [`algorithms.py:10-54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L10-L54):

* **FNV-1a**: An unsigned 32-bit hash used for partitioning ([`algorithms.py:28-33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L28-L33)).
* **CRC-32C (Castagnoli)**: A checksum used to verify record integrity, using the polynomial `0x1EDC6F41` ([`algorithms.py:18-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L18-L25)).
* **Java Array Hash**: A specific implementation of `java.util.Arrays.hashCode(byte[])` that treats bytes as signed values ([`algorithms.py:36-46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L36-L46)).

More: [The partitioner and checksum algorithms](conformance/the-partitioner-and)

## The wire protocol reference implementation [#the-wire-protocol-reference-implementation]

The `Connection` class in [`wire.py:62-170`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L62-L170) serves as a reference implementation of the protocol. It handles the mechanics of:

* **Framing**: Requests are prefixed with a length, an API key, a version, and a correlation ID ([`wire.py:81-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L81-L83)).
* **Decoding**: The `decode_records` function splits a payload into individual records and verifies the CRC of every complete record ([`wire.py:173-196`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L173-L196)).
* **Error Handling**: The `ProtocolError` class handles error codes such as `UNKNOWN_TOPIC_OR_PARTITION` or `OFFSET_OUT_OF_RANGE` ([`wire.py:29-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L29-L36)).

More: [The wire protocol reference implementation](conformance/the-wire-protocol)

## Producer and consumer verification scenarios [#producer-and-consumer-verification-scenarios]

The harness executes specific scenarios to catch silent failures, as detailed in [`scenarios.py:43-284`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L43-L284):

**Producer Checks:**

* `metadata_matches`: Ensures reported partitions match the broker's actual state ([`scenarios.py:72-78`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L72-L78)).
* `record_round_trip`: Verifies bytes are not altered in transit ([`scenarios.py:81-92`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L81-L92)).
* `empty_record_is_refused`: Ensures zero-length records trigger a `CORRUPT_REQUEST` ([`scenarios.py:95-115`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L95-L115)).
* `ack_none_does_not_wait`: Verifies that `ack=none` returns immediately without waiting for a response ([`scenarios.py:131-148`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L131-L148)).
* `partitioner_matches_vectors`: The core check ensuring the client's partition choice matches the golden vectors ([`scenarios.py:158-185`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L158-L185)).

**Consumer Checks:**

* `fetch_round_trip`: Verifies that fetched records match what was produced ([`scenarios.py:207-218`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L207-L218)).
* `truncated_tail`: Ensures a response that stops mid-record is handled by dropping the fragment ([`scenarios.py:232-249`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L232-L249)).
* `record_larger_than_max_bytes`: Verifies that a record exceeding `maxBytes` is reported via `recordExceedsMaxBytes` rather than causing a stall ([`scenarios.py:252-281`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L252-L281)).

## The conformance test runner [#the-conformance-test-runner]

The `run.py` script manages the lifecycle of the test suite ([`run.py:40-93`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L40-L93)). It performs the following steps:

1. Loads the partitioner vectors from a TSV file ([`run.py:20-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L20-L37)).
2. Initializes the `Client` and calls `load_capabilities` to determine which roles to test ([`run.py:51-58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L51-L58)).
3. Iterates through all registered `CHECKS` in `scenarios.py`, skipping those for which the client has not declared a role ([`run.py:65-70`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L65-L70)).
4. Reports the final count of passed, failed, and skipped checks ([`run.py:84-86`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L84-L86)).

## Key files [#key-files]

| File                                                                                                                                                                                        | Lines    | What is there                                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------ |
| [`…/harness/algorithms.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/algorithms.py#L10-L54 "conformance/harness/algorithms.py") | `10-54`  | Implementation of FNV-1a, Java array hash, and CRC-32C.      |
| [`…/harness/client.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L51-L113 "conformance/harness/client.py")            | `51-113` | The `Client` class for driving the command-line interface.   |
| [`…/harness/scenarios.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L43-L301 "conformance/harness/scenarios.py")   | `43-301` | The list of specific test scenarios and the `Context` class. |
| [`…/harness/run.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/run.py#L40-L93 "conformance/harness/run.py")                      | `40-93`  | The main entry point for running the conformance suite.      |
| [`…/harness/wire.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L62-L209 "conformance/harness/wire.py")                  | `62-209` | The reference implementation of the wire protocol.           |

## Behaviour that surprise [#behaviour-that-surprise]

* **Silent Stalls**: A consumer that encounters a truncated tail or a record larger than `maxBytes` without implementing the specific error handling logic will stall forever, appearing to the user as if it has simply caught up to the end of the log ([`scenarios.py:232-281`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L232-L281)).
* **The `ack=none` Trap**: In `produce` requests, `ack=none` means the broker sends absolutely nothing back; a client that waits for a response will block indefinitely ([`scenarios.py:131-148`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L131-L148)).
* **Partitioning Sensitivity**: Using a partition count that is a power of two can mask errors in the partitioner because the low bits of the hash and the partition ID may align by chance ([`README.md:103-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/README.md#L103-L108)).
