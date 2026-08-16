# CRC-32C Checksum Verification (/wiki/clients-node/crc-checksum-verification)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## The `crc32c` function and the Castagnoli polynomial [#the-crc32c-function-and-the-castagnoli-polynomial]

The implementation uses the Castagnoli polynomial `0x1EDC6F41`, which is distinct from the standard CRC-32 polynomial `0x04C11DB7` used by `zlib.crc32` ([`crc32c.js:3-6`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L3-L6)). Using the wrong polynomial is a "silent way to get this wrong," as it results in a stable but entirely incorrect sum that causes clients to reject every record ([`crc32c.js:24-25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L24-L25)).

## The `TABLE` and the reflected polynomial [#the-table-and-the-reflected-polynomial]

To optimize performance, the implementation uses a precomputed 256-entry lookup table ([`crc32c.js:42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L42)). This table is built using the **reflected** form of the polynomial, `0x82F63B78`, because the algorithm uses right-shifting operations ([`crc32c.js:15-16`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L15-L16)). This precomputation ensures that rebuilding the table per record would be prohibitively expensive ([`crc32c.py:47-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/crc32c.py#L47-L48)).

## The `RECORD_HEADER` and the `checksum` method [#the-record_header-and-the-checksum-method]

Data is framed in segments using a specific header format. The structure of a record is defined as follows:

| Field  | Size             | Description                        |
| ------ | ---------------- | ---------------------------------- |
| Length | `Int.SIZE_BYTES` | The size of the payload            |
| CRC    | `Int.SIZE_BYTES` | The CRC32C checksum of the payload |

The broker computes this sum using the `checksum` method, which updates a `java.util.zip.CRC32C` instance with the payload bytes ([`SegmentWriter.kt:93-95`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L93-L95)).

## The `fetch` response and `CorruptRecordException` [#the-fetch-response-and-corruptrecordexception]

Because the broker uses zero-copy mechanisms, it streams segment bytes to the socket without inspecting them ([`ResponseReader.kt:36-37`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L36-L37)). Consequently, the client is the only party capable of detecting corruption on the read path. If a record's bytes do not match the checksum provided in the frame, the client throws a `CorruptRecordException` ([`ResponseReader.kt:39-41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L39-L41)).

## The `recovery` process and torn records [#the-recovery-process-and-torn-records]

Checksums are critical during the log recovery process to handle "torn records"—records that were only partially written when a process crashed ([`SegmentWriter.kt:47-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L47-L48)). Without a checksum, recovery might mistake a partial body for valid data; with it, recovery can identify and stop at the first record where the bytes do not match the header ([`SegmentWriter.kt:72-73`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L72-L73)).

## The `conformance/vectors/crc32c.tsv` and `gate.sh` tests [#the-conformancevectorscrc32ctsv-and-gatesh-tests]

The implementation is pinned by conformance vectors to ensure mathematical correctness ([`crc32c.js:22`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L22)). Validation is performed through two layers:

* **Encoding/Decoding Consistency:** Tests run against a fake broker that decodes what the client encodes to catch encoding mistakes ([`README.md:120-121`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/README.md#L120-L121)).
* **Conformance:** Running against a real broker ensures the client is correct in a real-world environment ([`README.md:118`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/README.md#L118)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                                   | Lines   | What is there                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------- | ------------------------------------------------ |
| [`…/src/crc32c.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L24-L25 "clients/node/src/crc32c.js")                                                                                                                                  | `24-25` | The Castagnoli polynomial constant               |
| [`…/storage/SegmentWriter.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L88-L96 "booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt")             | `88-96` | The `checksum` method for computing payload sums |
| [`…/client/ResponseReader.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt#L39-L41 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/ResponseReader.kt") | `39-41` | The `CorruptRecordException` definition          |
| [`…/booblik/crc32c.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/crc32c.py#L31 "clients/python/booblik/crc32c.py")                                                                                                                      | `31`    | The reflected polynomial constant                |

## Behaviour that surprises [#behaviour-that-surprises]

* **Bitwise Arithmetic:** In JavaScript, the `>>> 0` operator is required at the end of the checksum calculation because bitwise operators produce signed 32-bit results, and a negative sum will fail to match the broker's unsigned sum ([`crc32c.js:17-19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L17-L19)).
* **Zero-Copy Verification:** Because the broker uses zero-copy, it cannot verify the data it sends; verification is strictly a client-side responsibility ([`SegmentWriter.kt:78-80`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-core/src/main/kotlin/ru/workinprogress/booblik/storage/SegmentWriter.kt#L78-L80)).
