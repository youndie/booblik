# clients/go (/wiki/clients-go)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `booblik` Go module is a client for the booblik message broker, providing the ability to produce, fetch, and retrieve metadata. It is designed to handle the complexities of an append-only log, including checksum validation, partition routing, and efficient batching for high-throughput writes.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Producer
    participant C as Conn
    participant B as Broker

    P->>C: Produce(records, ack)
    C->>B: Send Request (apiProduce)
    B-->>C: Response (BaseOffset, LogEndOffset)
    C-->>P: ProduceResult

    P->>C: Fetch(offset, maxBytes)
    C->>B: Send Request (apiFetch)
    B-->>C: Response (HighWatermark, Records)
    C-->>P: Records (Iterator/Range)"
/>

## Conn [#conn]

The `Conn` type represents a single TCP connection to a broker and is not safe for concurrent use because requests and responses are matched by a correlation ID in the order they are sent (`booblik.go:78-81`). Users should use one `Conn` per goroutine or utilize a `Producer` which manages its own connection (`booblik.go:78-81`). The `Dial` function opens this connection using a `net.Dialer` (`booblik.go:85-87`).

More: [Conn](clients-go/conn)

## ProduceResult [#produceresult]

A successful batch write returns a `ProduceResult` containing the offsets assigned to the batch (`booblik.go:70-71`). Specifically, it provides the `BaseOffset` and the `LogEndOffset` (`booblik.go:70-71`).

## AckPolicy [#ackpolicy]

The `AckPolicy` defines how long the broker waits before responding to a write request (`booblik.go:43-44`).

| Mode         | Value | Description                                                                                                 |
| ------------ | ----- | ----------------------------------------------------------------------------------------------------------- |
| `AckNone`    | 0     | No response is sent; the client receives no offset and the broker may drop the record (`booblik.go:50-51`). |
| `AckWritten` | 1     | The broker responds once the record is in the log, before any durability barrier (`booblik.go:52`).         |
| `AckForced`  | 2     | The broker responds after a `force()` operation, grouping all queued requests into one (`booblik.go:55`).   |

## Topic [#topic]

The `Topic` struct manages partition information and routing for a specific topic name (`booblik.go:281-284`). It provides the `PartitionFor` method to determine which partition a record should be sent to (`booblik.go:318`). If a `nil` key is provided, it uses a round-robin counter to select a partition (`booblik.go:318-320`).

## PartitionInfo [#partitioninfo]

`PartitionInfo` contains metadata retrieved via the `METADATA` API regarding the state of a partition (`booblik.go:59-67`).

| Field            | Type    | Description                                                               |
| ---------------- | ------- | ------------------------------------------------------------------------- |
| `Partition`      | `int32` | The partition ID (`booblik.go:60`)                                        |
| `LogStartOffset` | `int64` | The offset where the live log begins after retention (`booblik.go:62-64`) |
| `HighWatermark`  | `int64` | The first offset that does not exist yet (`booblik.go:66`)                |

## The Fetch mechanism [#the-fetch-mechanism]

Reading is performed via the `Records` method, which is a range-over-func iterator that runs on the caller's goroutine (`booblik.go:59-60`). The mechanism handles several edge cases:

* **Truncated Tails**: A response might be cut at a byte boundary by `MaxBytes`, resulting in a partial record where the client must stop at the last complete record (`booblik.go:95-96`).
* **MaxWait**: The broker can hold a request for up to `MaxWait` (default 5s) to allow more records to accumulate before responding (`booblik.go:92-93`).
* **High Watermark**: A consumer that has caught up will receive an empty response at the high watermark (`booblik.go:91-92`).

More: [The Fetch mechanism](clients-go/the-fetch-mechanism)

## Producer [#producer]

The `Producer` is an abstraction used when records arrive one at a time and need to be batched for performance (`booblik.go:40-41`). A `Producer` owns its own `Conn` and is the only writer to its socket to prevent interleaved responses (`booblik.go:50-52`). It is initialized via `NewProducer` and must be closed to flush queued records (`booblik.go:43-44`).

## Key files [#key-files]

| File                                                                                                                                                          | Lines     | What is there                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------ |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L21-L40 "clients/go/booblik.go")   | `21-40`   | Constants for API versions and header byte sizes |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L43-L56 "clients/go/booblik.go")   | `43-56`   | `AckPolicy` enumeration and descriptions         |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L59-L67 "clients/go/booblik.go")   | `59-67`   | `PartitionInfo` struct definition                |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L78-L81 "clients/go/booblik.go")   | `78-81`   | `Conn` struct and concurrency warnings           |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L281-L287 "clients/go/booblik.go") | `281-287` | `Topic` struct and round-robin field             |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L318-L323 "clients/go/booblik.go") | `318-323` | `PartitionFor` implementation                    |

## Behaviour that not obvious [#behaviour-that-not-obvious]

* `Conn.send` increments the `correlation` ID for every request, and `receive` validates that the response's correlation ID matches the expected one to prevent one caller from receiving another's answer (`booblik.go:125, 162`).
* `Topic.PartitionFor` with a `nil` key advances an internal `roundRobin` counter, meaning that calling `PartitionFor` twice without sending a message will result in two different partition selections (`booblik.go:318-320`).
* `Produce` with `AckNone` returns `(nil, nil)` because no offset exists until the broker reaches the batch, meaning there is no response to return (`booblik.go:262-263`).
