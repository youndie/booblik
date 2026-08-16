# Protocol Constants and Versioning (/wiki/booblik-protocol/protocol-constants-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant B as Broker
    Note over C,B: Frame: [Length][Header][Payload]
    C->>B: Send Request (with correlationId)
    alt Valid Request
        B->>C: Response (with same correlationId)
    else Invalid API Version
        B->>C: Error (UNSUPPORTED_VERSION + correlationId)
    else Corrupt Frame
        B->>C: Error (CORRUPT_REQUEST + 0)
    else Absurd Length
        B--xC: Connection Closed (EOF)
    end"
/>

## Protocol Constants [#protocol-constants]

The wire format relies on fixed-size headers and strict limits to ensure efficient parsing and resource protection. The following table summarizes the core constants defined in `Protocol.kt:15-58`:

| Constant                | Type                    | Description                                                         |
| ----------------------- | ----------------------- | ------------------------------------------------------------------- |
| `LENGTH_PREFIX_BYTES`   | `int32`                 | The size of the length prefix preceding every frame.                |
| `REQUEST_HEADER_BYTES`  | `int16 + int16 + int32` | The size of the request header (apiKey, apiVersion, correlationId). |
| `RESPONSE_HEADER_BYTES` | `int32 + int16`         | The size of the response header (correlationId, errorCode).         |
| `RECORD_HEADER_BYTES`   | `int32 + int32`         | The header preceding every stored record (length and CRC).          |
| `MAX_FRAME_BYTES`       | `8 MiB`                 | The ceiling on a single request to prevent heap exhaustion.         |
| `MAX_FETCH_WAIT_MILLIS` | `60,000 ms`             | The maximum time a broker may hold a `FETCH` request.               |

## ApiKey and Versioning Support [#apikey-and-versioning-support]

The protocol uses an `ApiKey` to identify the type of request, and versioning is handled per request rather than globally. As specified in `Protocol.kt:50-57`, the `supports` function determines compatibility:

* **`PRODUCE`**: Supports only `VERSION` (1).
* **`FETCH`**: Supports `VERSION` (1) and `FETCH_VERSION` (2).
* **`METADATA`**: Supports only `VERSION` (1).

## ErrorCode and Request Failure Modes [#errorcode-and-request-failure-modes]

Errors are communicated via the `ErrorCode` enum, which maps to specific protocol violations. The behavior of the `correlationId` during a failure is critical for pipelined clients, as detailed in `ErrorCodeTest.kt:15-20`:

| ErrorCode                    | ID | Condition                            | CorrelationId Behavior                  |
| ---------------------------- | -- | ------------------------------------ | --------------------------------------- |
| `NONE`                       | 0  | Success                              | N/A                                     |
| `UNKNOWN_TOPIC_OR_PARTITION` | 1  | Topic/Partition does not exist       | Echoed if header is parsed              |
| `OFFSET_OUT_OF_RANGE`        | 2  | `fetchOffset` is outside valid range | Echoed if header is parsed              |
| `RECORD_TOO_LARGE`           | 3  | Record exceeds segment limits        | Echoed if header is parsed              |
| `UNSUPPORTED_VERSION`        | 4  | Unknown `apiKey` or `apiVersion`     | Echoed if header is parsed              |
| `CORRUPT_REQUEST`            | 5  | Frame is malformed or unsatisfiable  | Echoed if header is parsed; otherwise 0 |

## RequestDecoder and Frame Validation [#requestdecoder-and-frame-validation]

The `RequestDecoder` is responsible for transforming raw bytes into structured `Request` objects. It performs rigorous bounds-checking to ensure that a remote party cannot cause memory issues, as seen in `Requests.kt:79-132`. Key validation mechanics include:

* **Header Integrity**: The `correlationId` is read first so it can be echoed even if the body is corrupt (`Requests.kt:93-96`).
* **Unsatisfiable Constraints**: In `decodeFetch`, if `minBytes > maxBytes`, a `CorruptRequestException` is thrown because the request can never be satisfied (`Requests.kt:209-210`).
* **Positive Counts**: `recordCount` in `PRODUCE` and `topicCount` in `METADATA` must be non-negative and must not exceed the remaining bytes in the buffer (`Requests.kt:149`, `Requests.kt:176`).

## Partial Frame Assembly and Socket Resilience [#partial-frame-assembly-and-socket-resilience]

The protocol is designed to handle fragmented network delivery. `PartialFrameTest.kt:29-40` demonstrates that a frame split across multiple packets (e.g., sending the length prefix, waiting, then sending the tail) is correctly assembled by the session. Furthermore, the protocol ensures connection resilience: if a frame is well-formed but contains an invalid `ApiKey` or `apiVersion`, the broker responds with an error but keeps the connection open (`ErrorCodeTest.kt:32-41`).

## Frame Length Limits and Resource Protection [#frame-length-limits-and-resource-protection]

To prevent Denial of Service (DoS) attacks where an attacker sends a massive `int32` length prefix to force large memory allocations, the broker enforces a strict limit. As tested in `PartialFrameTest.kt:112-129`, if a frame length exceeds the allowed bounds, the broker drops the connection immediately rather than attempting to allocate the requested memory.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                           | Lines    | What is there                                             |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | --------------------------------------------------------- |
| [`…/wire/Protocol.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L15-L58 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt") | `15-58`  | Protocol constants, `ApiKey` enum, and `ErrorCode` enum.  |
| [`…/net/PartialFrameTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L40-L62 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt")          | `40-62`  | Tests for split packets and frame assembly.               |
| [`…/net/ErrorCodeTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ErrorCodeTest.kt#L32-L51 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ErrorCodeTest.kt")                   | `32-51`  | Tests for error code responses and correlationId echoing. |
| [`…/wire/Requests.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt#L79-L132 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt")                | `79-132` | The `RequestDecoder` implementation and validation logic. |

## Behaviour that surprises [#behaviour-that-surprises]

* **CorrelationId Zeroing**: If a frame is so short that the `correlationId` cannot be parsed, the broker returns `0` as the ID in the error response (`ErrorCodeTest.kt:78-81`).
* **Zero-Copy and CRC**: The broker does not calculate the CRC for `FETCH` responses because it uses `transferTo` to move data directly from the disk to the socket, bypassing the heap ([`protocol-wire.md:154-156`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/api/protocol-wire.md#L154-L156)).
* **Unsatisfiable Fetch**: A `FETCH` request where `minBytes` is greater than `maxBytes` is rejected during decoding as a `CORRUPT_REQUEST` because it is logically impossible to satisfy (`Requests.kt:209-210`).
