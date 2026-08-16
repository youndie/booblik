# BrokerError and ProtocolError (/wiki/clients-node/brokererror-and-protocolerror)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

This module provides the error hierarchy and protocol-level error definitions for the `booblik` client. It distinguishes between failures that occur during the transport of bytes and failures that occur when the broker logic rejects a validly framed request.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant B as Broker
    C->>B: Request (Valid Frame)
    alt Broker rejects logic (e.g. Unknown Topic)
        B-->>C: BrokerError (ErrorCode)
        Note over C,B: Connection remains usable
    else Request is malformed/truncated
        B-->>C: ProtocolError
        Note over C,B: Connection is closed
    else Data is corrupted on disk
        C->>C: CorruptRecordError (Checksum mismatch)
    end"
/>

## BrokerError [#brokererror]

The mechanics of handling refusals from the broker where the connection remains usable. A `BrokerError` occurs when the framing is intact and the broker understands the request, but declines it based on business logic (e.g., an invalid offset or topic) [`errors.js:25-32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L25-L32). Because the framing is intact, the connection stays usable for subsequent requests [`errors.js:21-24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L21-L24). The `ErrorCode` returned by the broker is mapped to a human-readable name via `codeName` [`errors.js:14-16`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L14-L16).

## ProtocolError [#protocolerror]

Handling of transport-level failures and framing issues. A `ProtocolError` is raised when the bytes on the connection do not make sense, such as a bad length prefix, a short response, or a lost socket [`errors.js:35-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L35-L40). Unlike `BrokerError`, these issues often imply that the stream is no longer synchronized, potentially requiring a connection reset.

## CorruptRecordError [#corruptrecorderror]

The role of CRC-32C checksums in protecting the disk and the client's responsibility in verification. The client is the only party that can detect corruption because the broker uses zero-copy paths that do not touch the data bytes [`protocol-wire.md:154-156`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/api/protocol-wire.md#L154-L156). If a client skips checksum verification, it effectively disables the project's primary defense against log corruption [`errors.js:48-49`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L48-L49). The error includes the `offset`, `stored` checksum, and `computed` checksum to facilitate debugging [`errors.js:53-57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L53-L57).

## RecordExceedsMaxBytesError [#recordexceedsmaxbyteserror]

The distinction between broker-side record limits and client-side reading limits. This error is thrown when a record is larger than the client's own `maxBytes` limit, meaning the record can never be read in its entirety [`errors.js:67-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L67-L71). This is distinct from `Code.RECORD_TOO_LARGE`, which refers to the broker's refusal to store a record that is too big for a segment [`errors.js:73-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L73-L75).

## ErrorCode [#errorcode]

Enumeration of wire-level refusal codes.

| Code | Name                         | Description                                                                                                                                                                            |
| ---- | ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0    | `NONE`                       | Success                                                                                                                                                                                |
| 1    | `UNKNOWN_TOPIC_OR_PARTITION` | The specified topic or partition does not exist [`errors.go:10-11`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/errors.go#L10-L11)     |
| 2    | `OFFSET_OUT_OF_RANGE`        | The `fetchOffset` is outside the valid range [`protocol-wire.md:233`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/api/protocol-wire.md#L233) |
| 3    | `RECORD_TOO_LARGE`           | The record does not fit in a segment [`protocol-wire.md:234`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/api/protocol-wire.md#L234)         |
| 4    | `UNSUPPORTED_VERSION`        | Unknown `apiKey` or `apiVersion` [`errors.go:14-15`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/errors.go#L14-L15)                    |
| 5    | `CORRUPT_REQUEST`            | The frame cannot be parsed [`errors.go:15-16`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/errors.go#L15-L16)                          |

## Key files [#key-files]

| File                                                                                                                                                                  | Lines   | What is there                                     |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------- |
| [`…/src/errors.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L3-L32 "clients/node/src/errors.js")  | `3-32`  | Definition of `Code` enum and `BrokerError` class |
| [`…/src/errors.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L35-L40 "clients/node/src/errors.js") | `35-40` | Definition of `ProtocolError` class               |
| [`…/src/errors.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L51-L63 "clients/node/src/errors.js") | `51-63` | Definition of `CorruptRecordError` class          |
| [`…/src/errors.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L76-L86 "clients/node/src/errors.js") | `76-86` | Definition of `RecordExceedsMaxBytesError` class  |
| [`…/go/errors.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/errors.go#L10-L16 "clients/go/errors.go")              | `10-16` | Go implementation of error codes                  |

## Behaviour that does not surprise [#behaviour-that-does-not-surprise]

* `Session.handle` will respond with an error and keep the connection open if `RequestDecoder.decode` returns a `DecodeResult.Failed`, because the framing was intact [`Session.kt:86-89`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L86-L89).
* `Session.fetch` will return an empty response (not an error) if the `request.fetchOffset` is exactly equal to the `highWatermark` [`Session.kt:197-200`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L197-L200).
* `Session.produce` will return no response at all (silence) if `ackPolicy` is set to `NONE` [`Session.kt:169`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L169).
