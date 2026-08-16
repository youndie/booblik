# clients (/wiki/clients)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `clients` module provides a suite of client libraries for the booblik message broker, implemented in various languages to support both producing and consuming messages.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Client Libraries&#x22;
        Go[Go Client]
        Py[Python]
        Node[Node.js]
        Dot[DotNet]
        Java[Java]
        KN[Kotlin/Native]
    end

    subgraph &#x22;Broker&#x22;
        B[Booblik Broker]
    end

    Go -->|PRODUCE/FETCH/METADATA| B
    Py -->|PRODUCE/FETCH/METADATA| B
    Node -->|PRODUCE/FETCH/METADATA| B
    Dot -->|PRODUCE/FETCH/METADATA| B
    Java -->|PRODUCE/FETCH/METADATA| B
    KN -->|PRODUCE/FETCH/METADATA| B"
/>

## Client Implementations and Roles [#client-implementations-and-roles]

The module contains five reimplementations and one target. While most clients are interchangeable, Kotlin/Native is a target that shares the codec, IDs, and partitioner with the JVM client via `booblik-protocol` ([`README.md:21-24`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L21-L24)). The available implementations are:

| Language      | Package/Registry                        | Roles              |
| ------------- | --------------------------------------- | ------------------ |
| Go            | `github.com/youndie/booblik/clients/go` | producer, consumer |
| Python        | `booblik` on PyPI                       | producer, consumer |
| Node.js       | `booblik` on npm                        | producer, consumer |
| .NET          | `Booblik` on NuGet                      | producer, consumer |
| Java          | `booblik-java` on reposilite            | producer, consumer |
| Kotlin/Native | `booblik-native` on reposilite          | producer, consumer |

## The `Conn` Lifecycle and Concurrency [#the-conn-lifecycle-and-concurrency]

A `Conn` represents a single connection to a broker and is not safe for concurrent use because requests and responses are matched by a correlation ID in the order they are sent ([`booblik.go:75-77`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L75-L77)). In Go, the `Dial` function opens a TCP connection, and the `withContext` method is used to handle request deadlines by moving the deadline into the past to interrupt blocking operations ([`booblik.go:85-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L85-L103)).

More: [The `Conn` Lifecycle and Concurrency](clients/the-conn-lifecycle)

## The `Producer` and Batching Performance [#the-producer-and-batching-performance]

Batching is critical for performance; the broker's measurements show that batches of a hundred can reach 4,335,482 records/s, compared to only 80,592 records/s when sending one at a time ([`README.md:28-31`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/README.md#L28-L31)). Users can batch manually or use a `Producer` wrapper which owns its own `Conn` to ensure a single writer to the socket ([`README.md:33-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L33-L45)). The `AckPolicy` determines how the client waits for the broker:

| Mode         | Behavior                                                                                                                                                                                             |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AckNone`    | No response is sent; the broker may drop the record silently ([`booblik.go:50-51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L50-L51))  |
| `AckWritten` | Answers once the record is in the log, before any durability barrier ([`booblik.go:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L52)) |
| `AckForced`  | Answers after a `force()` operation ([`booblik.go:54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L54))                                  |

## The `Topic` and Partitioning Logic [#the-topic-and-partitioning-logic]

`Topic` objects are obtained by calling `Metadata` on a connection, which returns information about partitions, including the `LogStartOffset` and `HighWatermark` ([`booblik.go:59-67`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L59-L67)). Partitioning is handled by `PartitionFor`, which is a pure function of the key; if no key is provided, it advances a round-robin counter ([`booblik.go:318-322`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L318-L322)).

More: [The `Topic` and Partitioning Logic](clients/the-topic-and)

## The `Consumer` and Position Management [#the-consumer-and-position-management]

Reading is performed via iterators or streams, such as Go's `Records` range-over-func iterator ([`README.md:58-64`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/README.md#L58-L64)). Consumers must manage their own state, as the broker does not handle consumer groups or committed offsets; the reader is responsible for persisting the `Position` after records are dealt with to ensure correct re-delivery upon restart ([`README.md:77-81`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/README.md#L77-L81)). If a record is larger than `MaxBytes`, it may be cut at a byte boundary, requiring the client to handle the fragment ([`README.md:99-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L99-L103)).

## Conformance and Gatekeeping [#conformance-and-gatekeeping]

Compliance is ensured through a conformance kit that runs fourteen checks against every client. Each client directory must contain a `gate.sh` for its own ecosystem checks and a `conformance-client.sh` to execute the client under test ([`README.md:78-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L78-L80)). The `gate.sh` script for .NET will exit with code 77 if the .NET SDK is not found, allowing the top-level CI to mark the client as skipped ([`gate.sh:15-18`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/gate.sh#L15-L18)).

## Key files [#key-files]

| File                                                                                                                                                                 | Lines   | What is there                                        |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ---------------------------------------------------- |
| [`clients/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L11-L19 "clients/README.md")                | `11-19` | Table of client coordinates, roles, and dependencies |
| [`…/go/booblik.go`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L21-L40 "clients/go/booblik.go")          | `21-40` | Constants for API versions and header byte sizes     |
| [`…/dotnet/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L20-L47 "clients/dotnet/README.md") | `20-47` | Documentation on batching and the `Producer` wrapper |

## Behaviour that surprise [#behaviour-that-surprise]

* `AckNone` in `Produce` returns `(nil, nil)` in Go and does not result in an empty response, but rather no response at all, because no offset exists until the writer reaches the batch ([`booblik.go:262-263`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L262-L263)).
* The `Position` in a consumer is the responsibility of the client; persisting it *after* processing records is what prevents re-delivery issues during a restart ([`README.md:89-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L89-L90)).
* `PartitionFor(nil)` in Go advances a round-robin counter, meaning that calling it and then sending a message results in two turns of the counter ([`booblik.go:318-320`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/go/booblik.go#L318-L320)).
