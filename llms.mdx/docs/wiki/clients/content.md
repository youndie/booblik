# clients (/wiki/clients)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The `clients` module provides a suite of client libraries for the booblik protocol, ensuring that various programming languages can interact with the broker as both producers and consumers.

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Client Implementations&#x22;
        GO[Go]
        PY[Python]
        JS[Node.js]
        DOT[dotnet]
        JAVA[Java]
        KN[Kotlin/Native]
    end

    subgraph &#x22;Protocol & Shared Logic&#x22;
        BP[booblik-protocol]
    end

    GO -->|implements| BP
    PY -->|implements| BP
    JS -->|implements| BP
    DOT -->|implements| BP
    JAVA -->|implements| BP
    KN -->|is a target of| BP"
/>

## Client Implementations [#client-implementations]

The module provides six client libraries, five of which are reimplementations of the protocol and one which is a multiplatform target ([`README.md:21`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L21)). All six libraries are designed to act as both producer and consumer, and all six must pass the same fourteen conformance checks ([`README.md:6-7`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L6-L7)).

| Language       | Coordinate                              | Roles              | Dependencies  |
| -------------- | --------------------------------------- | ------------------ | ------------- |
| Go             | `github.com/youndie/booblik/clients/go` | producer, consumer | none          |
| Python         | `booblik` on PyPI                       | producer, consumer | none          |
| Python-asyncio | `booblik.aio`                           | producer, consumer | none          |
| Node.js        | `booblik` on npm                        | producer, consumer | none          |
| .NET           | `Booblik` on NuGet                      | producer, consumer | xunit (tests) |
| Java           | `booblik-java` on reposilite            | producer, consumer | JUnit (tests) |
| Kotlin/Native  | `booblik-native` on reposilite          | producer, consumer | none          |

## Kotlin/Native Multiplatform Target [#kotlinnative-multiplatform-target]

Unlike the other clients, the Kotlin/Native client is not a reimplementation but a target of the shared `booblik-protocol` ([`README.md:5-8`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L5-L8)). It shares the codec, the IDs, and the partitioner with the JVM client, allowing it to compile for both `linuxX64` and `macosArm64` from a single source ([`README.md:22-23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L22-L23)).

More: [Kotlin/Native Multiplatform Target](clients/kotlin-native-multiplatform)

## Batching and Throughput [#batching-and-throughput]

Performance is heavily dependent on batching; sending one record per request is considered the most expensive mistake possible ([`README.md:22-23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L22-L23)). The broker's measurements show that batches of a hundred can achieve 4,335,482 records/s, compared to only 80,592 records/s when sent one at a time ([`README.md:23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L23)).

## Producer and Consumer Mechanics [#producer-and-consumer-mechanics]

In the .NET implementation, a `Producer` owns its `Connection` and acts as the sole writer to the socket; using the same `Connection` directly while a `Producer` is active can lead to mismatched responses ([`README.md:44-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L44-L45)). For reading, the .NET client uses `IAsyncEnumerable` to provide back-pressure, ensuring the next fetch does not occur until the current loop body is finished ([`README.md:81-82`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L81-L82)).

More: [Producer and Consumer Mechanics](clients/producer-and-consumer)

## CRC-32C Checksum Implementation [#crc-32c-checksum-implementation]

Checksum verification requires handling language-specific "traps" regarding integer types and hardware instructions ([`README.md:37-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L37-L40)).

| Language      | Implementation Detail                                                                                                                                                                                    |
| ------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Go            | Uses standard library with hardware instruction ([`README.md:42-43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L42-L43))                        |
| Python        | Requires reading the stored sum as unsigned ([`README.md:51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L51))                                   |
| JavaScript    | Requires `>>> 0` because bitwise operators produce signed 32-bit results ([`README.md:51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L51))      |
| Java          | Requires a cast from `CRC32C.getValue()`'s `long` ([`README.md:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L52))                             |
| C#            | Uses `unchecked` because FNV-1a arithmetic must wrap at 32 bits ([`README.md:65-66`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L65-L66)) |
| Kotlin/Native | Uses a reflected polynomial in a 256-entry table ([`README.md:77-78`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L77-L78))         |

More: [CRC-32C Checksum Implementation](clients/crc-checksum-implementation)

## Conformance and Gatekeeping [#conformance-and-gatekeeping]

Each client directory must contain `gate.sh` for ecosystem-specific checks and `conformance-client.sh` to execute the client under test ([`README.md:79-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L79-L80)). The `conformance-client.sh` script for .NET builds the assembly only if the source files are newer than the existing DLL ([`conformance-client.sh:13-14`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/conformance-client.sh#L13-L14)).

## Key files [#key-files]

| File                                                                                                                                                                                                                          | Lines   | What is there                                                             |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------------------------- |
| [`clients/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L13-L19 "clients/README.md")                                                                         | `13-19` | Table of client coordinates, roles, and dependencies                      |
| [`…/dotnet/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L20-L42 "clients/dotnet/README.md")                                                          | `20-42` | Documentation on batching and the `Producer` class                        |
| [`…/dotnet/gate.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/gate.sh#L22-L23 "clients/dotnet/gate.sh")                                                                | `22-23` | Command to run `dotnet test`                                              |
| [`…/kotlin-native/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L28-L31 "clients/kotlin-native/README.md")                                     | `28-31` | Explanation of the synchronous nature of the Native client                |
| [`…/kotlin-native/conformance-client.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/conformance-client.sh#L18-L31 "clients/kotlin-native/conformance-client.sh") | `18-31` | Logic to select the correct `TARGET` and `LINK_TASK` based on the host OS |

## Behaviour that surprise [#behaviour-that-surprise]

* `AckPolicy.None` in .NET results in nothing being returned at all, as no offset exists until the writer reaches the batch ([`README.md:51-52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L51-L52)).
* The `key` is never actually sent to the broker; the client calculates the partition and sends the number instead ([`README.md:55-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L55-L56)).
* `PartitionFor(null)` in both .NET and Kotlin/Native advances a round-robin counter, meaning the first call and the subsequent send constitute two turns of the counter ([`README.md:62`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L62) and [`README.md:95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L95)).
