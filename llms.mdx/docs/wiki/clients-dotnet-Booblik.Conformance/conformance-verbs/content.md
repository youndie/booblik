# Conformance Verbs (/wiki/clients-dotnet-Booblik.Conformance/conformance-verbs)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant H as Harness (Python)
    participant C as Client (Dotnet/Go)
    participant B as Broker

    H->>C: capabilities
    C-->>H: roles=producer,consumer
    
    H->>C: metadata(topic)
    C->>B: MetadataRequest
    B-->>C: PartitionInfo
    C-->>H: partition=X logStartOffset=Y highWatermark=Z

    H->>C: produce(topic, partition, ack, hex)
    C->>B: ProduceRequest
    alt AckPolicy.None
        B-->>C: (No response)
        C-->>H: (Immediate return)
    else AckPolicy.Written/Forced
        B-->>C: ProduceResponse
        C-->>H: baseOffset=X logEndOffset=Y
    end

    H->>C: fetch(topic, partition, offset, maxBytes)
    C->>B: FetchRequest
    B-->>C: Records (or Truncated)
    C-->>H: record=HEX"
/>

## The `capabilities` verb [#the-capabilities-verb]

The initial handshake where the client declares its roles and name. The harness uses this to determine which verbs the client is allowed to execute, as seen in [`client.py:100-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L100-L108). The client must respond with `roles` (a comma-separated list) and a `name` identifier, as implemented in [`Program.cs:21-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L21-L22).

## The `metadata` verb [#the-metadata-verb]

Retrieving partition information including log start offsets and high watermarks. The client requests metadata for a specific topic and iterates through the returned partition information to print the partition ID, the log start offset, and the high watermark, as demonstrated in [`Program.cs:105-108`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L105-L108).

## The `produce` verb and `AckPolicy` [#the-produce-verb-and-ackpolicy]

Mechanics of record submission, the behavior of `AckPolicy.None` (returning immediately), and the handling of zero-length records. The `ack` parameter maps to specific policies:

| Policy    | Value               | Behavior                                                                                                                                                                                                                      |
| --------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `none`    | `AckPolicy.None`    | Client returns immediately; no response is expected from the broker ([`Program.cs:121`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L121)) |
| `written` | `AckPolicy.Written` | Client awaits confirmation of the write ([`Program.cs:122`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L122))                             |
| `forced`  | `AckPolicy.Forced`  | Client awaits a stronger durability guarantee ([`Program.cs:123`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L123))                       |

The harness specifically checks that `produce` with `none` does not wait for a response, as an empty field is treated as a zero-length record which the broker may refuse ([`Program.cs:127-128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L127-L128)).

## The `produce-keyed` verb and partitioner logic [#the-produce-keyed-verb-and-partitioner-logic]

How the client-side partitioner selects a partition based on a key before the broker sees it, and validation against golden vectors. In this mode, the client performs the partitioning logic locally using the key, as seen in [`Program.cs:152`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L152). The correctness of this logic is validated by comparing the client's choice against "golden vectors" in [`partition_test.go:18-36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/partition_test.go#L18-L36).

## The `fetch` verb and record truncation [#the-fetch-verb-and-record-truncation]

Edge cases in fetching: handling truncated records when `maxBytes` is exceeded and empty responses at the high watermark. The client must handle cases where a record is larger than the requested `maxBytes`, resulting in a truncated response where `answer.Truncated` is true ([`Program.cs:85-87`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L85-L87)). It also handles empty responses when the fetch offset is at the high watermark ([`Program.cs:85`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L85)).

## Broker refusal as a result [#broker-refusal-as-a-result]

The distinction between program failure and a valid broker refusal (e.g., `error=CODE`), and how exit codes are used to signal success. A broker refusal is considered a valid result of a command, not a failure of the client program; in such cases, the client prints `error=CODE` and exits with code 0 ([`Program.cs:61-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L61-L65)). A non-zero exit code is reserved for actual program failures, such as unknown verbs or missing environment variables ([`Program.cs:57-58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L57-L58)).

## Key files [#key-files]

| File                                                                                                                                                                                                                           | Lines     | What is there                                                 |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ------------------------------------------------------------- |
| [`…/Booblik.Conformance/Program.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L111-L139 "clients/dotnet/Booblik.Conformance/Program.cs") | `111-139` | Implementation of the `Produce` verb and `AckPolicy` handling |
| [`…/go/partition_test.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/partition_test.go#L17-L46 "clients/go/partition_test.go")                                               | `17-46`   | Test verifying the partitioner against golden vectors         |
| [`…/harness/client.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/client.py#L100-L108 "conformance/harness/client.py")                                              | `100-108` | Logic for loading and validating client capabilities          |

## Behaviour that surprises [#behaviour-that-surprises]

* **Immediate Return**: When using `AckPolicy.None`, the client must not wait for a response from the broker; doing so causes a timeout in the harness ([`Program.cs:133`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L133)).
* **Refusal vs. Failure**: A `BrokerException` is not a program error; the client must exit with `0` if the broker refuses a request, reporting the error via `stdout` instead ([`Program.cs:63-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L63-L64)).
* **Client-Side Partitioning**: In `produce-keyed`, the partition is chosen by the client using the key, meaning the broker never sees the key itself ([`Program.cs:141-142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L141-L142)).
