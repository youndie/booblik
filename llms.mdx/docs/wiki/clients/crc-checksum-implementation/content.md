# CRC-32C Checksum Implementation (/wiki/clients/crc-checksum-implementation)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="graph TD
    A[Producer] -->|Writes Payload + CRC| B[Broker]
    B -->|Zero-Copy Read Path| C[Consumer/Client]
    C -->|Verifies Checksum| D{Match?}
    D -->|Yes| E[Process Record]
    D -->|No| F[Error/Stop]
    B -.->|Recovery| G[Log Segment]
    G -.->|Detects Torn Records| B"
/>

## The CRC-32C Polynomial and Reflected Tables [#the-crc-32c-polynomial-and-reflected-tables]

The implementation uses the Castagnoli polynomial `0x1EDC6F41`. Because many implementations, such as the one in [`crc32c.js:32`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L32), utilize right-shifting, the table must be built using the reflected form of the polynomial, which is `0x82F63B78` ([`crc32c.js:25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L25)). Using the standard polynomial with a right-shift results in a "stable, plausible, everywhere-wrong sum" ([`crc32c.js:16`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L16)).

## The Zero-Copy Read Path and Client-Side Verification [#the-zero-copy-read-path-and-client-side-verification]

A critical design decision in the booblik protocol is that the broker does not touch the payload bytes on the read path to enable zero-copy performance; consequently, the responsibility for verifying the checksum shifts entirely to the client ([`SegmentWriter.kt:78-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L78-L80)). While the broker computes the checksum on write, the client is the only party on the read path capable of performing the verification ([`SegmentWriter.kt:79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L79)).

## The Recovery Process and Torn Records [#the-recovery-process-and-torn-records]

The checksum serves as a vital sentinel during the broker's startup recovery process. Because a length prefix alone cannot distinguish between a valid record and a partially written (torn) record after a crash, the checksum allows recovery to stop at the first record whose bytes do not match their own header ([`SegmentWriter.kt:73-74`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L73-L74)).

## Language-Specific Arithmetic Traps [#language-specific-arithmetic-traps]

Different programming languages require specific handling to manage 32-bit integer arithmetic and sign bits:

* **JavaScript**: Requires the `>>> 0` operator to ensure bitwise operations produce unsigned 32-bit results rather than signed ones ([`crc32c.js:57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L57)).
* **Java**: Requires a cast from the `long` returned by `CRC32C.getValue()` to an `int` ([`README.md:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L52)).
* **Python**: Must handle the fact that its integers never overflow, requiring specific logic to match the expected signed/unsigned behavior ([`README.md:38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L38)).
* **C#**: Does not require special handling because a cast between `int` and `uint` preserves the bits ([`README.md:55`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L55)).

## Conformance and Golden Vectors [#conformance-and-golden-vectors]

To ensure correctness across all implementations, the module is validated against "golden vectors" ([`README.md:37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/README.md#L37)). These vectors, stored in `conformance/vectors/crc32c.tsv`, are used to catch errors where an implementation might be self-consistent but mathematically incorrect, such as using a mis-derived reflected polynomial ([`README.md:83-84`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/kotlin-native/README.md#L83-L84)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                       | Lines   | What is there                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | ---------------------------------------------------------- |
| [`…/src/crc32c.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L25-L39 "clients/node/src/crc32c.js")                                                                                                                      | `25-39` | The reflected polynomial and the table building logic      |
| [`…/storage/SegmentWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L93-L96 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt") | `93-96` | The Java-based CRC32C implementation used for checksumming |
| [`…/src/crc32c.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L52-L57 "clients/node/src/crc32c.js")                                                                                                                      | `52-57` | The JavaScript implementation of the `crc32c` function     |

## Behaviour that surprises [#behaviour-that-surprises]

* The `crc32c` function in JavaScript requires a `>>> 0` at the end because bitwise operators produce signed 32-bit results, which would otherwise cause the sum to be negative half the time ([`crc32c.js:17-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L17-L19)).
* In the .NET client, the `Crc32C` implementation is provided via forty lines of code in `Crc32C.cs` rather than a NuGet package to avoid making verification an optional dependency ([`README.md:94-96`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L94-L96)).
* A `Producer` in .NET returns `null` for `ProduceAsync` when using `AckPolicy.None`, as no offset exists until the batch reaches the broker ([`README.md:51-52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L51-L52)).
