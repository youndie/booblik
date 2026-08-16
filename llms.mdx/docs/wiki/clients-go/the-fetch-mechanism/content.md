# The Fetch mechanism (/wiki/clients-go/the-fetch-mechanism)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Consumer
    participant B as Broker
    C->>B: FetchRequest (Offset, MaxBytes, MaxWait, MinBytes)
    alt No new data & MaxWait not reached
        B-->>C: Empty Response (HighWatermark)
    else Data available
        B->>B: Checksum records (CRC-32C)
        B-->>C: Fetched (Records, HighWatermark, Truncated?)
    end
    C->>C: Verify Checksum (CRC-32C)
    alt Checksum Mismatch
        C-->>C: CorruptRecordError
    else Success
        C->>C: Advance Position
    end"
/>

## FetchRequest [#fetchrequest]

The `FetchRequest` struct defines the parameters that govern how a consumer requests data from a partition [`consumer.go:94-112`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L94-L112). The request includes the target `Topic`, `Partition`, and the starting `Offset`.

| Parameter  | Type            | Description                                                     |
| ---------- | --------------- | --------------------------------------------------------------- |
| `Offset`   | `int64`         | The starting point for reading.                                 |
| `MaxBytes` | `int32`         | Bounds the response in bytes, not records.                      |
| `MaxWait`  | `time.Duration` | How long the broker holds a request if no data is available.    |
| `MinBytes` | `int32`         | The minimum amount of data required before the broker responds. |

A critical distinction exists between `MaxBytes` and the size of individual records: `MaxBytes` limits the total response size, which can cause a response to end in the middle of a record [`consumer.go:100-101`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L100-L101).

## Fetched [#fetched]

A successful response is encapsulated in the `Fetched` struct [`consumer.go:115-129`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L115-L129). It contains the `HighWatermark`, which represents the first offset that does not yet exist in the log. The `Records` field is a slice of byte slices that point directly into the response frame without copying the data, meaning the caller must copy any data they wish to retain beyond the lifetime of the frame [`consumer.go:118-120`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L118-L120).

## Truncation and MaxBytes [#truncation-and-maxbytes]

Because `MaxBytes` limits the response size in bytes, a response may end inside a record. This is indicated by the `Truncated` flag in the `Fetched` struct [`consumer.go:123-125`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L123-L125). When this occurs, the `TruncatedRecordBytes` field provides the size of the record that was cut off, allowing the client to understand why the response was incomplete [`consumer.go:127-128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L127-L128).

## CorruptRecordError [#corruptrecorderror]

The client is the only party capable of detecting data corruption on the read path because the broker uses zero-copy to stream segment bytes directly to the socket without inspecting them [`consumer.go:41-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L41-L43). The client verifies each record using CRC-32C (Castagnoli) via the `Checksum` function [`consumer.go:36`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L36). If the computed checksum does not match the stored checksum, a `CorruptRecordError` is returned, which includes the `Offset`, the `Stored` checksum, and the `Computed` checksum [`consumer.go:57-69`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L57-L69).

## RecordExceedsMaxBytesError [#recordexceedsmaxbyteserror]

A permanent stall occurs if a single record is larger than the consumer's `MaxBytes` limit. In this scenario, the broker returns a truncated response, and the client identifies this as a `RecordExceedsMaxBytesError` [`consumer.go:54-55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L54-L55). This error carries the `Offset`, the `RecordBytes` required, and the current `MaxBytes` limit to inform the caller that they must increase their buffer to proceed [`consumer.go:74-85`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L74-L85).

## Consumer Position and Lag [#consumer-position-and-lag]

The `Consumer` manages the `position` (the offset of the next record to be read) and the `highWatermark` [`consumer.go:243-250`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L243-L250). The position is only advanced past whole records during a `Poll` operation [`consumer.go:347`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L347). `Lag` is calculated as the difference between the `highWatermark` and the current `position`, representing how many records the consumer is behind the head of the log [`consumer.go:306-310`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L306-L310).

## Records Iterator [#records-iterator]

The `Records` method provides a range-over-func iterator that yields records one at a time [`consumer.go:376-377`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L376-L377). This iterator runs on the caller's goroutine at the caller's pace, avoiding the issues of buffered channels such as leaking goroutines or silently advancing the position past unhandled records [`consumer.go:366-371`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L366-L371).

## Key files [#key-files]

| File                                                                                                                                                             | Lines     | What is there                                  |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ---------------------------------------------- |
| [`…/go/consumer.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L94-L112 "clients/go/consumer.go")  | `94-112`  | `FetchRequest` struct definition               |
| [`…/go/consumer.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L115-L129 "clients/go/consumer.go") | `115-129` | `Fetched` struct definition                    |
| [`…/go/consumer.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L57-L69 "clients/go/consumer.go")   | `57-69`   | `CorruptRecordError` struct definition         |
| [`…/go/consumer.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L74-L85 "clients/go/consumer.go")   | `74-85`   | `RecordExceedsMaxBytesError` struct definition |
| [`…/go/consumer.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L243-L250 "clients/go/consumer.go") | `243-250` | `Consumer` struct definition                   |

## Behaviour that surprises [#behaviour-that-surprises]

* `Poll` only advances the `position` past whole records; if a response is truncated, the position remains at the start of the partial record to allow for a retry with a larger `MaxBytes` [`consumer.go:347`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L347).
* The `Records` iterator does not use a channel; it executes on the caller's goroutine to ensure that the `position` is not advanced until the caller has actually processed the records [`consumer.go:371`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/consumer.go#L371).
