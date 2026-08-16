# The wire protocol reference implementation (/wiki/conformance/the-wire-protocol)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant S as Session (Broker)
    participant L as Log/Partition

    C->>S: Send Frame (Length + Header + Payload)
    Note over S: readFrame() validates length
    S->>S: RequestDecoder.decode(frame)
    alt DecodeResult.Ok
        S->>S: handle(request)
        alt PartitionRequest
            S->>L: openFetch / append
            L-->>S: slice / baseOffset
            S->>C: Response (Header + Body)
        else MetadataRequest
            S->>C: Metadata Response
        end
    else DecodeResult.Failed
        S->>C: Error Response (ErrorCode)
    end
    alt CorruptRequestException
        S--x C: Drop Connection
    end"
/>

## The Connection framing and length prefixing [#the-connection-framing-and-length-prefixing]

The protocol uses a length-prefixed framing mechanism where each message is preceded by a 4-byte integer indicating the size of the following frame [`Protocol.kt:24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L24). In the Python reference implementation, the `_send` method packs the API key, version, and correlation ID into a header before sending the payload, all wrapped in a length prefix [`wire.py:81-82`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L81-L82). The `_receive` method performs the inverse, first reading the 4-byte length and then the exact number of bytes required to complete the frame [`wire.py:86-87`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L86-L87).

## Request decoding and `DecodeResult` [#request-decoding-and-decoderesult]

Decoding is a two-stage process designed to protect the broker. First, the `RequestDecoder` attempts to parse the header to identify the `ApiKey` and `correlationId` [`Requests.kt:79-96`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt#L79-L96). If the header is valid, the decoder proceeds to parse the body; if the header itself is malformed, it returns a `DecodeResult.Failed` containing an `UNKNOWN_CORRELATION_ID` to prevent the broker attempting to respond to an invalid ID [`Requests.kt:97-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt#L97-L98).

## The `Session` protocol loop [#the-session-protocol-loop]

The `Session` class manages the lifecycle of a single connection through a continuous loop in the `serve` function [`Session.kt:47-53`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L47-L53). The loop follows a strict sequence:

1. `readFrame()`: Reads the next complete frame from the connection [`Session.kt:50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L50).
2. `handle(frame)`: Dispatches the decoded request [`Session.kt:51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L51).
3. `respondError` or `writeFully`: Sends the response back to the client `booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt:88, 151`.
   This loop ensures that requests are served strictly in order, one at a time, to maintain consistency with the client's expectation of response ordering [`Session.kt:30-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L30-L34).

## The `FetchMode` and zero-copy path [#the-fetchmode-and-zero-copy-path]

The broker supports two distinct modes for transferring data during a `fetch` request [`Session.kt:219-230`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L219-L230):

| Mode                  | Description                                                                                                                                                                                                                                                            |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FetchMode.ZERO_COPY` | Uses `connection.transferFrom` to move data directly from the log segment to the socket [`Session.kt:221`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L221) |
| `FetchMode.HEAP`      | Reads data into a reusable `staging` buffer before writing to the connection [`Session.kt:225`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L225)            |

## Handling partial frames and connection survival [#handling-partial-frames-and-connection-survival]

The protocol is designed to be robust against network fragmentation. A frame may be split across multiple TCP packets; the `Session` handles this by reading the full length of the frame before attempting to decode it [`Session.kt:68-71`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L68-L71). However, if a frame claims an "absurd" length that exceeds `Protocol.MAX_FRAME_BYTES`, the broker throws a `CorruptRequestException` and drops the connection to protect its memory `booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt:69, booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt:31`. Tests in [`PartialFrameTest.kt:112-128`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L112-L128) verify that while split packets are assembled correctly, invalid lengths result in connection closure.

## The `awaitRecords` mechanism [#the-awaitrecords-mechanism]

When a `FetchRequest` includes a `maxWaitMillis` and `minBytes` requirement, the broker enters a suspension loop in `awaitRecords` [`Session.kt:246-267`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L246-L267). Instead of busy-waiting, the coroutine suspends using `handle.writer.highWatermark.first { it > seen }`, which resumes only when the log's watermark advances [`Session.kt:261`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L261). This ensures the broker does not waste CPU cycles while waiting for more data to become available in the log.

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                           | Lines    | What is there                                         |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ----------------------------------------------------- |
| [`…/wire/Protocol.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L15-L58 "booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt") | `15-58`  | Protocol constants and API Key/Error Code definitions |
| [`…/net/Session.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt#L37-L298 "booblik-net/src/main/kotlin/ru/workinprogress/booblik/net/Session.kt")                                    | `37-298` | The main protocol loop and request handling logic     |
| [`…/wire/Requests.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt#L79-L184 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt")                | `79-184` | Request data structures and the `RequestDecoder`      |

## Behaviour that does surprise [#behaviour-that-does-surprise]

* **Silent Silence**: In `produce` requests, if the `ackPolicy` is set to `ACK_NONE`, the broker returns no response at all rather than an empty response, because an offset does not exist until the writer reaches the batch [`wire.py:149-150`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/wire.py#L149-L150).
* **The Metadata Exception**: Unlike other requests, `MetadataRequest` is dispatched before the topic and partition are even parsed, because it is the only request that does not name a specific partition [`Requests.kt:110-115`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/wire/Requests.kt#L110-L115).
* **Clamped Waiting**: If a client requests a `maxWaitMillis` longer than 60 seconds, the broker clamps the wait to `MAX_FETCH_WAIT_MILLIS` to prevent holding coroutines and sockets open indefinitely [`Protocol.kt:44`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-protocol/src/commonMain/kotlin/ru/workinprogress/booblik/net/wire/Protocol.kt#L44).
