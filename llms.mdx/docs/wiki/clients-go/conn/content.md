# Conn (/wiki/clients-go/conn)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `Conn` type is the fundamental transport layer for the `booblik` Go client. It manages a single TCP connection to a broker and handles the low-level framing of the wire protocol. It is responsible for serializing request headers, managing correlation IDs to ensure request-response integrity, and enforcing protocol-level constraints like maximum frame sizes.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant G as Goroutine
    participant C as Conn
    participant B as Broker

    G->>C: send(apiKey, version, payload)
    Note over C: Increment correlationId
    C->>B: Write [Length][Header][Payload]
    B-->>C: Write [Length][Header][Payload]
    C->>C: receive(expectedCorrelation)
    Note over C: Validate correlation & CodeNone
    C-->>G: Return payload or error"
/>

## The `Conn` lifecycle and `Dial` [#the-conn-lifecycle-and-dial]

A connection is established using the `Dial` function, which takes a `context.Context` to bound the initial connection attempt ([`booblik.go:85-94`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L85-L94)). Once established, the connection is not safe for concurrent use because requests and responses are matched by a strictly incrementing `correlation` ID ([`booblik.go:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L75-L77)). To ensure low latency for the small requests typical of this protocol, the client relies on `TCP_NODELAY` being enabled by default in Go ([`booblik.go:91-93`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L91-L93)).

## Request-Response matching via `correlation` [#request-response-matching-via-correlation]

To ensure that a response is matched to the correct caller, the `Conn` maintains an internal `correlation` counter ([`booblik.go:80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L80)). Every time `send` is called, the `correlation` is incremented and included in the request header ([`booblik.go:125-132`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L125-L132)). During the `receive` phase, the client explicitly checks that the `correlation` ID returned by the broker matches the one expected for that specific request; if they do not match, the client returns an error to prevent one caller from receiving another's data ([`booblik.go:162-164`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L162-L164)).

## Context-driven deadlines and `withContext` [#context-driven-deadlines-and-withcontext]

Since Go's standard socket operations do not natively support `context.Context` for interruption, `Conn` implements a `withContext` method to bridge this gap ([`booblik.go:103-122`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L103-L122)). This method works by moving the connection's deadline into the past to interrupt blocking operations if the context is cancelled or reaches its deadline ([`booblik.go:104-114`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L104-L114)). It returns an undo function that restores the deadline once the exchange is complete ([`booblik.go:118-121`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L118-L121)).

## The `receive` mechanism and frame validation [#the-receive-mechanism-and-frame-validation]

The `receive` method performs strict validation on incoming data to protect the client from malformed or malicious responses ([`booblik.go:141-168`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L141-L168)). The validation steps include:

* Reading the 4-byte length prefix ([`booblik.go:144-146`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L144-L146)).
* Ensuring the length is within the bounds of `responseHeaderBytes` and `maxFrameBytes` ([`booblik.go:147-149`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L147-L149)).
* Verifying the `correlation` ID matches the expected value ([`booblik.go:162-164`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L162-L164)).
* Checking that the response `code` is `CodeNone` ([`booblik.go:165-167`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L165-L167)).

## Error handling and connection stability [#error-handling-and-connection-stability]

The client distinguishes between protocol errors and transport errors to maintain connection stability ([`booblik_test.go:74-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik_test.go#L74-L90)).

* **Usable Errors:** Errors like `UNKNOWN_TOPIC_OR_PARTITION` are considered protocol-level refusals; because the framing remains intact, the connection remains usable for subsequent requests ([`booblik_test.go:76-88`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik_test.go#L76-L88)).
* **Fatal Errors:** If a response frame length is out of range, the connection is effectively compromised and cannot be safely reused ([`booblik.go:147-149`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L147-L149)).

## Testing `AckNone` and response timeouts [#testing-acknone-and-response-timeouts]

The client must handle the `AckNone` policy, where the broker does not send a response ([`booblik.go:50-52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L50-L52)). Tests verify that when `AckNone` is used, the `Produce` call returns immediately with a `nil` result and no error, rather than waiting for a response that will never arrive ([`booblik_test.go:57-72`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik_test.go#L57-L72)).

## Key files [#key-files]

| File                                                                                                                                                                       | Lines     | What is there                                   |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------- |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L78-L81 "clients/go/booblik.go")                | `78-81`   | Definition of the `Conn` struct and its fields. |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L124-L139 "clients/go/booblik.go")              | `124-139` | The `send` method implementation.               |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L141-L169 "clients/go/booblik.go")              | `141-169` | The `receive` method implementation.            |
| [`…/go/booblik_test.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik_test.go#L22-L47 "clients/go/booblik_test.go") | `22-47`   | Round-trip production testing.                  |
| [`…/go/booblik_test.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik_test.go#L52-L72 "clients/go/booblik_test.go") | `52-72`   | Testing for `AckNone` behavior.                 |

## Behaviour that surprises [#behaviour-that-surprises]

* `Conn.send` increments the `correlation` ID internally, meaning the caller does not manage the sequence of IDs ([`booblik.go:125`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L125)).
* Using `AckNone` in a `Produce` call results in a `nil` `ProduceResult` because there is no response to parse ([`booblik.go:262-263`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L262-L263)).
* A `Conn` is not thread-safe; sharing a single `Conn` between goroutines will cause them to read each other's responses due to the shared `correlation` state ([`booblik.go:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L75-L77)).
