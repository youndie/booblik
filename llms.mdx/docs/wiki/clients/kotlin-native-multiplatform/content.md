# Kotlin/Native Multiplatform Target (/wiki/clients/kotlin-native-multiplatform)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    subgraph &#x22;Shared Logic&#x22;
        P[booblik-protocol]
    end
    subgraph &#x22;Kotlin/Native Target&#x22;
        N[booblik-native]
        C[conformance-client.sh]
    end
    subgraph &#x22;External&#x22;
        B[Real Broker]
    end
    P -->|Codec, IDs, Partitioner| N
    N -->|Executes| C
    C -->|Tests against| B"
/>

## The shared protocol and booblik-protocol [#the-shared-protocol-and-booblik-protocol]

The Kotlin/Native client is not a reimplementation of the protocol but a target of the shared protocol. The codec, the IDs, and the partitioner are all sourced from `:booblik-protocol`, which compiles for the JVM and both native targets from a single source ([`build.gradle.kts:37-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/build.gradle.kts#L37-L38)). This ensures that the native client is a true target of the shared protocol rather than a fifth implementation.

## Blocking POSIX sockets and the absence of Dispatchers.IO [#blocking-posix-sockets-and-the-absence-of-dispatchersio]

The client uses blocking POSIX sockets and does not utilize coroutines for the core connection logic. Because `Dispatchers.IO` is `internal` in the Kotlin/Native runtime ([`build.gradle.kts:18-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/build.gradle.kts#L18-L19)), there is no standard IO pool to offload blocking socket calls onto. Consequently, the client remains synchronous, and the `Sequence` used in the consumer is chosen specifically because there is nothing to suspend on when the socket is a blocking POSIX one ([`README.md:54-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L54-L56)).

## The Producer accumulator and newSingleThreadContext [#the-producer-accumulator-and-newsinglethreadcontext]

While the `Connection` remains blocking, the `Producer` acts as an accumulator. Because the standard IO dispatcher is unavailable, the concurrency model requires a dedicated thread; specifically, `newSingleThreadContext` is used so that the producer owns a thread where both the loop and the socket reside ([`build.gradle.kts:20-22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/build.gradle.kts#L20-L22)).

## CRC32C checksum and the reflected polynomial trap [#crc32c-checksum-and-the-reflected-polynomial-trap]

Verifying a record's checksum is the responsibility of the client. This is implemented via `expect`/`actual` to allow the JVM to use its intrinsic `CRC32C` while Kotlin/Native uses a 256-entry table ([`README.md:72-78`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L72-L78)). A significant edge case occurred during development where a hand-derived negative literal `-0x7D644AC8` was used, which actually represents `0x829BB538`, resulting in a stable but incorrect sum ([`README.md:82-84`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L82-L84)).

## The gate.sh and conformance-client.sh validation [#the-gatesh-and-conformance-clientsh-validation]

Validation is performed through two distinct scripts. `gate.sh` is used for local checks, selecting a target that the host can actually execute, such as `linuxX64` or `macosArm64` ([`README.md:100-104`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L100-L104)). The `conformance-client.sh` script handles the heavy lifting of building the binary if necessary and then executing it against a real broker ([`conformance-client.sh:37-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/conformance-client.sh#L37-L38)).

## Key files [#key-files]

| File                                                                                                                                                                                                                          | Lines   | What is there                                           |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------------------------- |
| [`booblik-native/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/build.gradle.kts#L37-L38 "booblik-native/build.gradle.kts")                               | `37-38` | Declaration of the shared `booblik-protocol` dependency |
| [`booblik-native/build.gradle.kts`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-native/build.gradle.kts#L18-L19 "booblik-native/build.gradle.kts")                               | `18-19` | Note on the `internal` status of `Dispatchers.IO`       |
| [`…/kotlin-native/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L54-L56 "clients/kotlin-native/README.md")                                     | `54-56` | Explanation of why `Sequence` is used instead of `Flow` |
| [`…/kotlin-native/conformance-client.sh`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/conformance-client.sh#L37-L38 "clients/kotlin-native/conformance-client.sh") | `37-38` | Logic for building and executing the binary             |

## Behaviour that surprise [#behaviour-that-surprise]

* `produce` returns `null` when `AckPolicy.NONE` is used, because no offset exists until the writer reaches the batch ([`README.md:89-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L89-L90)).
* `partitionFor(null)` is not a static lookup but advances a round-robin counter ([`README.md:95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L95)).
* The `position` in a consumer is managed by the client and must be persisted *after* records are dealt with to ensure restart reliability ([`README.md:62-63`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L62-L63)).
