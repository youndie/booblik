# FakeBroker (/wiki/clients-dotnet-Booblik.Tests/fakebroker)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant FB as FakeBroker
    participant L as Log (In-Memory)

    C->>FB: TCP Connection
    Note over FB: ServeAsync loop
    FB->>FB: Decode Frame (API Key, Version, Correlation)
    alt ApiProduce
        FB->>L: Append Records
        FB-->>C: Response (Offsets)
    else ApiFetch
        FB->>L: Read from Offset
        FB-->>C: Response (Records + CRC)
    else ApiMetadata
        FB-->>C: Response (Topic/Partition Info)
    end"
/>

## FakeBroker [#fakebroker]

The `FakeBroker` is a specialized test fixture designed to speak the protocol well enough to answer requests and, crucially, to decode what the client encoded rather than simply pattern-matching raw bytes [`FakeBroker.cs:9-15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L9-L15). This ensures that an encoding mistake in the client results in a decode failure within the broker, providing a clear signal of protocol violations [`FakeBroker.cs:9-15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L9-L15).

## ServeAsync [#serveasync]

The `ServeAsync` method implements the core request-response loop [`FakeBroker.cs:105-168`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L105-L168). It reads a 4-byte length prefix, followed by the frame, and extracts the API key, version, and correlation ID [`FakeBroker.cs:121-134`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L121-L134). Based on the `apiKey`, it dispatches the payload to the appropriate handler:

* `ApiProduce` (1) [`FakeBroker.cs:137`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L137)
* `ApiFetch` (2) [`FakeBroker.cs:148`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L148)
* `ApiMetadata` (3) [`FakeBroker.cs:152`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L152)

## Produce [#produce]

The `Produce` method decodes the topic name, partition, and the batch of records [`FakeBroker.cs:177-197`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L177-L197). It handles the `AckPolicy` to determine if the broker should respond with offsets or remain silent [`FakeBroker.cs:185-227`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L185-L227).

| AckPolicy    | Behavior                                                                                                                                                                                                            |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `None`       | Returns an empty body and `Silent = true` [`FakeBroker.cs:218-222`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L218-L222)          |
| `All/Leader` | Returns the base offset and the new high watermark [`FakeBroker.cs:224-226`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L224-L226) |

## Fetch [#fetch]

The `Fetch` method performs log traversal by reading records starting from a specific offset [`FakeBroker.cs:280`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L280). It implements the following logic:

* **Truncation**: It uses `maxBytes` to cut the response, which can result in a partial record at the end of the payload [`FakeBroker.cs:289-293`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L289-L293).
* **Checksums**: It calculates `CRC32C` for each record [`FakeBroker.cs:284`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L284).
* **High Watermark**: It includes the current log end offset in the response [`FakeBroker.cs:291-292`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L291-L292).

## Metadata [#metadata]

The `Metadata` method allows clients to discover the cluster state [`FakeBroker.cs:297-349`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L297-L349). It encodes:

* The number of topics [`FakeBroker.cs:299`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L299)
* Topic names and their partition counts [`FakeBroker.cs:304-307`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L304-L307)
* For each partition: the partition ID, the current log start offset, and the high watermark [`FakeBroker.cs:337-345`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L337-L345).

## Seed [#seed]

The `Seed` method allows tests to manually inject records into a specific topic and partition without going through the full `Produce` request cycle [`FakeBroker.cs:234-246`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L234-L246). This is used to set up specific consumer states for testing [`FakeBroker.cs:234-246`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L234-L246).

## Corrupt [#corrupt]

To test the robustness of the client's error handling, the `Corrupt` property can be set to `true` [`FakeBroker.cs:36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L36). When enabled, the broker flips a bit in every stored checksum during a `Fetch` operation, simulating a damaged segment on disk [`FakeBroker.cs:34-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L34-L37).

## Key files [#key-files]

| File                                                                                                                                                                                                                  | Lines     | What is there                                                                               |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------- |
| [`…/Booblik.Tests/FakeBroker.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L17-L46 "clients/dotnet/Booblik.Tests/FakeBroker.cs")   | `17-46`   | The `FakeBroker` class definition and its public properties like `Corrupt` and `LastFetch`. |
| [`…/Booblik.Tests/FakeBroker.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L175-L227 "clients/dotnet/Booblik.Tests/FakeBroker.cs") | `175-227` | The `Produce` method implementation.                                                        |
| [`…/Booblik.Tests/FakeBroker.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L257-L295 "clients/dotnet/Booblik.Tests/FakeBroker.cs") | `257-295` | The `Fetch` method implementation.                                                          |
| [`…/Booblik.Tests/FakeBroker.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/FakeBroker.cs#L297-L349 "clients/dotnet/Booblik.Tests/FakeBroker.cs") | `297-349` | The `Metadata` method implementation.                                                       |
