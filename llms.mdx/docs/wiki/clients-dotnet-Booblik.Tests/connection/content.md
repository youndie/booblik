# Connection (/wiki/clients-dotnet-Booblik.Tests/connection)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Connection` component manages the lifecycle and communication between a client and a Booblik broker. It is responsible for establishing asynchronous connections, handling the framing of requests and responses, managing data transfer efficiency via zero-copy mechanisms, and ensuring that protocol errors are correctly distinguished from connection-level failures.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client
    participant S as SocketChannel
    participant B as Broker

    C->>S: ConnectAsync
    S-->>C: Connection Established
    C->>S: ProduceAsync (Frame)
    S->>B: TCP Stream
    Note over B: Broker processes request
    B->>S: Response (or nothing if AckPolicy.None)
    S-->>C: Decode Response/Error"
/>

## Connection.ConnectAsync [#connectionconnectasync]

The asynchronous establishment of a connection to a broker is the entry point for client interaction, as seen in [`ConnectionTests.cs:22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L22) and [`connection.test.js:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/connection.test.js#L19). This process initializes the underlying transport and prepares the client to send framed requests.

## AckPolicy.None [#ackpolicynone]

The behavior of asynchronous production when no response is expected from the broker is governed by `AckPolicy.None`. When this policy is used, the client does not wait for a response from the broker, which is verified by ensuring the `produce` task returns `null` or completes without waiting for a broker acknowledgement ([`connection.test.js:46`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/connection.test.js#L46) and [`ServerTest.kt:65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/ServerTest.kt#L65)).

## ProtocolException and ProtocolError [#protocolexception-and-protocolerror]

Handling of malformed frames and truncated responses during decoding is critical for stability. A response that is cut short by a broker restart is treated as a `ProtocolException` rather than a standard decoding error ([`ConnectionTests.cs:102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L102)), and such malformed data must trigger a `ProtocolError` to prevent the caller from receiving invalid state ([`connection.test.js:88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/connection.test.js#L88)).

## SocketChannel and transferTo [#socketchannel-and-transferto]

The mechanics of zero-copy data transfer rely on the `transferTo` method, which requires the underlying object to be a real `SocketChannel` to utilize the JDK's direct `sendfile` path ([`SendfileTest.kt:72`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L72)). If the connection is wrapped in a decorator (like a metrics or TLS layer), the direct path is lost, even though the resulting bytes remain identical ([`SendfileTest.kt:101`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L101)).

## Partial Frame Assembly [#partial-frame-assembly]

The system is designed to handle fragmented network traffic through robust assembly logic:

* **Split Packets:** A frame split across two packets is correctly assembled once the remaining bytes arrive ([`PartialFrameTest.kt:40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L40)).
* **Byte-at-a-time:** Data delivered one byte at a time is still successfully assembled into a single request ([`PartialFrameTest.kt:65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L65)).
* **Absurd Lengths:** If a frame specifies an absurdly large length, the broker must drop the connection rather than attempting to allocate massive amounts of memory ([`PartialFrameTest.kt:112`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L112)).

## Broker Refusals [#broker-refusals]

The distinction between protocol errors that close a connection and errors that allow connection reuse is vital for reliability. A refusal (such as `UnknownTopicOrPartition`) is a protocol-level error that does not close the connection if the framing remains intact ([`ConnectionTests.cs:53`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L53)), whereas a frame length out of range is a framing error that must close the connection ([`ConnectionTests.cs:61`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L61)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                  | Lines   | What is there                                                     |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------------------- |
| [`…/Booblik.Tests/ConnectionTests.cs`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L16-L31 "clients/dotnet/Booblik.Tests/ConnectionTests.cs")                                                    | `16-31` | Tests for byte-for-byte record arrival and offset verification    |
| [`…/net/PartialFrameTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt#L40-L57 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PartialFrameTest.kt") | `40-57` | Tests for frame splitting and delayed packet arrival              |
| [`…/net/SendfileTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L69-L78 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt")             | `69-78` | Verification that `transferTo` maintains the `SocketChannel` type |
| [`…/test/connection.test.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/connection.test.js#L28-L38 "clients/node/test/connection.test.js")                                                                                   | `28-38` | Node.js implementation of byte-for-byte record testing            |
| [`…/harness/scenarios.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/conformance/harness/scenarios.py#L81-L94 "conformance/harness/scenarios.py")                                                                                              | `81-94` | Python conformance check for byte-for-byte payload integrity      |

## Behaviour that すれthought [#behaviour-that-すれthought]

* `AckPolicy.None` results in the client not receiving an offset, as the broker sends nothing back ([`connection.test.js:48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/connection.test.js#L48)).
* A `ProtocolException` is specifically used to signal that a response was truncated, distinguishing it from a standard `RangeError` during decoding ([`ConnectionTests.cs:102`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/Booblik.Tests/ConnectionTests.cs#L102)).
* The `transferTo` path is highly sensitive to object wrapping; wrapping a `SocketChannel` in a decorator can silently disable zero-copy optimizations ([`SendfileTest.kt:40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/SendfileTest.kt#L40)).
