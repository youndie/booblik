# clients/dotnet/Booblik.Conformance (/wiki/clients-dotnet-Booblik.Conformance)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 2. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant Harness as Test Harness
    participant Client as Booblik.Conformance (Exe)
    participant Broker as Booblik Broker
    
    Harness->>Client: Execute Verb (args[0])
    alt Verb is 'capabilities'
        Client-->>Harness: stdout: roles=producer,consumer
    else Verb is 'produce'/'fetch'/'metadata'
        Client->>Broker: Connection.ConnectAsync
        alt Success
            Client->>Broker: Request (Produce/Fetch/Metadata)
            Broker-->>Client: Response (Data or BrokerException)
            Client-->>Harness: stdout: key=value OR error=CODE
        else Broker Refusal
            Client-->>Harness: stdout: error=CODE (Exit 0)
        end
    end"
/>

## Conformance Verbs [#conformance-verbs]

The command-line interface and supported operations for testing the broker. The program accepts a verb as the first argument `args[0]` and uses a `switch` statement to dispatch to specific logic [`Program.cs:38-59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L38-L59). Supported verbs include `capabilities`, `metadata`, `produce`, `produce-keyed`, and `fetch`.

More: [Conformance Verbs](clients-dotnet-Booblik.Conformance/conformance-verbs)

## Metadata Retrieval [#metadata-retrieval]

Fetching partition information, log start offsets, and high watermarks. The `Metadata` function calls `connection.MetadataAsync` to retrieve information for a specific topic [`Program.cs:97-109`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L97-L109). For each partition found, it outputs the partition ID, the `LogStartOffset`, and the `HighWatermark` [`Program.cs:107`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L107).

More: [Metadata Retrieval](clients-dotnet-Booblik.Conformance/metadata-retrieval)

## Produce Operations [#produce-operations]

Sending records with different `AckPolicy` settings and keyed partitioning logic.

| Ack Policy | Mapping             |
| ---------- | ------------------- |
| `none`     | `AckPolicy.None`    |
| `written`  | `AckPolicy.Written` |
| `forced`   | `AckPolicy.Forced`  |

The `Produce` function handles hex-encoded payloads and different acknowledgment requirements [`Program.cs:111-139`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L111-L139). Additionally, `ProduceKeyed` exercises the partitioner by using `topic.PartitionFor(key)` to determine the destination partition before sending the record [`Program.cs:143-158`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L143-L158).

## Fetch Operations [#fetch-operations]

Retrieving records and handling edge cases like truncated tails and `maxBytes` limits. The `Fetch` function retrieves records via `connection.FetchAsync` and reports the `HighWatermark` [`Program.cs:72-81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L72-L81). It specifically handles cases where a response is truncated because a record exceeds the `maxBytes` limit, reporting `recordExceedsMaxBytes` [`Program.cs:85-89`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L85-L89).

## Broker Refusal Handling [#broker-refusal-handling]

How the client reports `BrokerException` results via stdout without exiting with error. When a `BrokerException` is caught, the program prints `error={refusal.Code.WireName()}` to `stdout` and exits with code 0, as a refusal is considered a valid result rather than a program failure [`Program.cs:61-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L61-L65).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                        | Lines   | What is there                                                         |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------------------------------------------------------------- |
| [`…/Booblik.Conformance/Booblik.Conformance.csproj`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Booblik.Conformance.csproj#L1-L21 "clients/dotnet/Booblik.Conformance/Booblik.Conformance.csproj") | `1-21`  | Project configuration for a .NET 8.0 console application.             |
| [`…/Booblik.Conformance/Program.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L1-L158 "clients/dotnet/Booblik.Conformance/Program.cs")                                                | `1-158` | The main entry point containing the conformance test logic and verbs. |

## Behaviour that surprise [#behaviour-that-surprise]

* A `BrokerException` is caught and reported as a successful execution (exit code 0) with an `error=CODE` message on `stdout` rather than a process failure [`Program.cs:61-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L61-L65).
* In `ProduceKeyed`, the client performs the partitioning logic itself using `topic.PartitionFor(key)` because the broker does not receive the key [`Program.cs:141-152`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L141-L152).
* When `Fetch` encounters a truncated record due to size limits, it reports `recordExceedsMaxBytes` instead of simply returning an empty list [`Program.cs:85-89`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Conformance/Program.cs#L85-L89).
