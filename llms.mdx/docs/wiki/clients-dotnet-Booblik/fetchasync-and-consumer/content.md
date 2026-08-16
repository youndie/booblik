# FetchAsync and Consumer (/wiki/clients-dotnet-Booblik/fetchasync-and-consumer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

Documentation for the data retrieval and consumption layer of the Booblik client.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer
    participant Conn as Connection
    participant B as Broker

    C->>Conn: FetchAsync(offset, maxBytes, maxWait)
    Note over Conn: Send Request (v2)
    Conn->>B: ApiFetch (Request)
    
    alt No new data & maxWaitMillis > 0
        Note over B: Broker holds request
        B-->>Conn: Response (HighWatermark, Payload)
    else Data available
        B-->>Conn: Response (HighWatermark, Payload)
    end

    Note over Conn: Decode(body, offset)
    Conn->>C: PollAsync() -> IReadOnlyList<byte[]>
    C->>C: Update Position & HighWatermark"
/>

## FetchAsync mechanics [#fetchasync-mechanics]

The `FetchAsync` method implements the low-level request/response cycle for retrieving data from a specific partition. It strictly uses the v2 protocol version to ensure a unified code path for all clients [`Connection.cs:239-270`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L239-L270). The request is parameterized by several key constraints:

| Parameter       | Description                                                                                                                                                                                                               |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `topic`         | The name of the topic to read from                                                                                                                                                                                        |
| `partition`     | The specific partition index                                                                                                                                                                                              |
| `offset`        | The starting offset for the fetch                                                                                                                                                                                         |
| `maxBytes`      | The maximum byte size of the response [`Connection.cs:248`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L248)                                   |
| `maxWaitMillis` | How long the broker may hold a request that has nothing to answer with [`Connection.cs:250`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L250)  |
| `minBytes`      | The minimum amount of data the broker must accumulate before responding [`Connection.cs:251`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L251) |

## The Fetched record structure [#the-fetched-record-structure]

A `Fetched` record represents a single response from the broker, containing the state of the log at the time of the request [`Consumer.cs:16-20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L16-L20). The structure includes:

| Field                  | Description                                                                                                                                                                                                          |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `HighWatermark`        | The first offset that does not exist yet [`Consumer.cs:17`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L17)                                 |
| `Records`              | The list of successfully decoded records                                                                                                                                                                             |
| `Truncated`            | Indicates if the response ended inside a record due to `maxBytes` limits [`Consumer.cs:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L19) |
| `TruncatedRecordBytes` | The size of the dropped record if `Truncated` is true [`Consumer.cs:20`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L20)                    |

The response frame contains a "promised" payload length which is checked against the actual received bytes to detect if the response was cut short by a broker restart [`Consumer.cs:176-180`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L176-L180).

## Consumer position and Lag [#consumer-position-and-lag]

The `Consumer` manages the local read position, which is the offset of the next record to be read [`Consumer.cs:55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L55).

* **Position**: The offset the consumer is currently at. It is advanced only after a successful poll [`Consumer.cs:106`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L106).
* **HighWatermark**: A snapshot of the log end at the last poll [`Consumer.cs:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L60).
* **Lag**: Calculated as the difference between the `HighWatermark` and the `Position`, ensuring it never returns a negative value [`Consumer.cs:64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L64).

## PollAsync and RecordsAsync [#pollasync-and-recordsasync]

The consumer provides two ways to consume data:

1. **`PollAsync`**: A discrete method that fetches a batch of records and advances the `Position` [`Consumer.cs:94`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L94).
2. **`RecordsAsync`**: An `IAsyncEnumerable` stream that yields records one at a time [`Consumer.cs:139`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L139). This interface applies back-pressure because the next fetch does not occur until the loop body is finished [`Consumer.cs:131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L131).

An empty list returned by a poll is not an end-of-log signal, but a steady state for a caught-up consumer [`Consumer.cs:78`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L78).

## Record checksum verification [#record-checksum-verification]

The decoding process involves unframing the `FETCH` response and verifying the integrity of every record [`Consumer.cs:155`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L155).

* **CRC32C Validation**: Every record is checked using `Crc32C.Compute(record)` against the stored checksum [`Consumer.cs:214-217`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L214-L217).
* **`CorruptRecordException`**: Thrown when the computed checksum does not match the stored one [`Consumer.cs:217`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L217).
* **`RecordExceedsMaxBytesException`**: Thrown when a response is truncated and the next record's size exceeds the consumer's `MaxBytes` limit, causing a potential stall [`Consumer.cs:102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L102).

## Subscription cost and Long FETCH [#subscription-cost-and-long-fetch]

The performance of a consumer depends on its polling strategy, which is measured by the ratio of request frequency to broker load [`SubscriptionProbe.kt:37-41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L37-L41).

| Strategy       | Mechanism                         | Impact                                                                                                                                                                                                                                                                                                 |
| -------------- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Polling**    | Uses a fixed `pollIntervalMillis` | High request volume even when idle [`SubscriptionProbe.kt:150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L150)                                                |
| **Long FETCH** | Uses `maxWaitMillis`              | Reduces request volume by allowing the broker to hold requests until data arrives [`SubscriptionProbe.kt:163`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L163) |

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                                             | Lines     | What is there                                |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | -------------------------------------------- |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L139-L164 "clients/dotnet/Booblik/Connection.cs")                                                                                                                              | `139-164` | Metadata decoding logic                      |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L183-L223 "clients/dotnet/Booblik/Connection.cs")                                                                                                                              | `183-223` | ProduceAsync implementation                  |
| [`…/Booblik/Connection.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Connection.cs#L244-L270 "clients/dotnet/Booblik/Connection.cs")                                                                                                                              | `244-270` | FetchAsync implementation                    |
| [`…/Booblik/Consumer.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L38-L70 "clients/dotnet/Booblik/Consumer.cs")                                                                                                                                      | `38-70`   | Consumer class definition and properties     |
| [`…/Booblik/Consumer.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L155-L227 "clients/dotnet/Booblik/Consumer.cs")                                                                                                                                    | `155-227` | Fetch response decoding and CRC verification |
| [`…/probe/SubscriptionProbe.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt#L129-L174 "booblik-benchmark/src/main/kotlin/ru/workinprogress/booblik/benchmark/probe/SubscriptionProbe.kt") | `129-174` | Benchmarking of polling vs long FETCH        |

## Behaviour that surprises [#behaviour-that-surprises]

* `Consumer.PollAsync` advances the `Position` only after a successful fetch, meaning a crash between handling and saving the position results in at-least-once delivery (replaying the batch) [`Main.kt:79-83`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/consumer/src/main/kotlin/ru/workinprogress/booblik/dev/consumer/Main.kt#L79-L83).
* `Consumer.RecordsAsync` is an `IAsyncEnumerable` that applies back-pressure by construction; the next fetch does not happen until the consumer's loop body is complete [`Consumer.cs:131`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L131).
* A response that is truncated because it hits the `maxBytes` limit is not considered a corruption, but a routine event [`Consumer.cs:212`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik/Consumer.cs#L212).
