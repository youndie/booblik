# clients/node (/wiki/clients-node)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (Connection/Consumer)
    participant B as Broker (or FakeBroker)
    
    C->>B: Send Request (PRODUCE/FETCH/METADATA)
    Note over C,B: Request includes Correlation ID
    B-->>C: Response (with same Correlation ID)
    alt Success
        C->>C: Decode & Verify Checksum (CRC-32C)
    else Broker Refusal
        C->>C: Throw BrokerError
    else Protocol Mismatch
        C->>C: Throw ProtocolError
    end"
/>

## BrokerError and ProtocolError [#brokererror-and-protocolerror]

The module distinguishes between errors caused by the broker's logic and errors caused by the transport layer. A `BrokerError` is raised when the broker understands the request but declines it (e.g., `UNKNOWN_TOPIC_OR_PARTITION), as defined in [`errors.js:25-33`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L25-L33). In contrast, a `ProtocolError` is raised when the bytes on the connection do not make sense, such as a bad length or a lost socket, as seen in [`errors.js:35-40\`]\([https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L35-L40](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L35-L40)).

More: [BrokerError and ProtocolError](clients-node/brokererror-and-protocolerror)

## FNV-1a Partitioning [#fnv-1a-partitioning]

To ensure deterministic record placement, the client uses the FNV-1a hash algorithm to map keys to specific partitions. The `fnv1a32` function in [`partition.js:28-35`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/partition.js#L28-L35) implements this using `Math.imul` to ensure 32-bit integer wraparound, which is critical because standard JavaScript numbers are doubles. The `partitionFor` function then folds this hash into the available partition count [`partition.js:48-52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/partition.js#L48-L52).

More: [FNV-1a Partitioning](clients-node/fnv-partitioning)

## CRC-32C Checksum Verification [#crc-32c-checksum-verification]

Data integrity is maintained via the CRC-32C (Castagnoli) polynomial `0x1EDC6F41` [`crc32c.js:25`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L25). The implementation in [`crc32c.js:25-38`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L25-L38) uses a pre-computed table for performance. Because JavaScript bitwise operators produce signed 32-bit results, the implementation uses `>>> 0` to ensure the final result is an unsigned 32-bit integer, preventing mismatches with the broker [`crc32c.js:57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L57).

More: [CRC-32C Checksum Verification](clients-node/crc-checksum-verification)

## Connection Framing and Pipelining [#connection-framing-and-pipelining]

The `Connection` class manages the lifecycle of a socket and supports pipelining by using a FIFO queue of pending requests [`connection.js:50`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L50). Each request is assigned a `correlationId` to ensure that responses are matched to the correct caller, even if they arrive out of order relative to the request stream [`connection.js:141-153`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L141-L153). The framing logic uses a 4-byte length prefix to delineate messages [`connection.js:97-105`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L97-L105).

## Topic and Partition Management [#topic-and-partition-management]

The client provides high-level abstractions for interacting with topics. The `topic` method in [`connection.js:280-284`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L280-L284) retrieves metadata from the broker to determine available partitions. Once a `Topic` is obtained, the `partitionFor` method handles routing: it uses the FNV-1a hash for keyed records or a round-robin approach for null keys [`connection.js:311-316`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L311-L316).

## Consumer Polling and Async Iteration [#consumer-polling-and-async-iteration]

The `Consumer` class manages the reading of a partition, tracking the `position` (the offset of the next record to be read) [`consumer.js:111`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/consumer.js#L111). It provides an async iterator via the `records()` method, which uses `poll()` to fetch batches of data [`consumer.js:189-192`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/consumer.js#L189-L192). If a fetch returns a record that is larger than the `maxBytes` limit, a `RecordExceedsMaxBytesError` is thrown to prevent infinite retry loops [`consumer.js:156-161`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/consumer.js#L156-L161).

## FakeBroker for Conformance Testing [#fakebroker-for-conformance-testing]

The `FakeBroker` class in `clients/node/test/fake-broker.js` is used to verify that the client correctly encodes requests and decodes responses. Unlike a real broker, it is designed to decode the client's payload to ensure the client's encoding is valid, rather than just pattern-matching bytes [`fake-broker.js:5-7`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/fake-broker.js#L5-L7). It can also simulate data corruption to test the client's checksum verification [`fake-broker.js:178`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/fake-broker.js#L178).

## Key files [#key-files]

| File                                                                                                                                                                                    | Lines     | What is there                                          |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------------ |
| [`…/src/errors.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/errors.js#L3-L10 "clients/node/src/errors.js")                    | `3-10`    | The `Code` object containing broker refusal constants. |
| [`…/src/partition.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/partition.js#L28-L35 "clients/node/src/partition.js")          | `28-35`   | The `fnv1a32` function for 32-bit FNV-1a hashing.      |
| [`…/src/crc32c.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/crc32c.js#L25 "clients/node/src/crc32c.js")                       | `25`      | The `POLYNOMIAL` constant for CRC-32C.                 |
| [`…/src/connection.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L141-L153 "clients/node/src/connection.js")     | `141-153` | The `#send` method for framing and sending requests.   |
| [`…/src/consumer.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/consumer.js#L105-L120 "clients/node/src/consumer.js")           | `105-120` | The `Consumer` class constructor and properties.       |
| [`…/test/fake-broker.js`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/test/fake-broker.js#L17-L40 "clients/node/test/fake-broker.js") | `17-40`   | The `FakeBroker` class definition.                     |

## Behaviour that surprise [#behaviour-that-surprise]

* The `partitionFor` function in `Topic` uses a round-robin counter that advances on every call when the key is null, meaning that calling `partitionFor` multiple times without sending a record will cause the client to skip partitions [`connection.js:305-307`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L305-L307).
* The `Consumer.poll` method advances the `position` by the number of records returned, but if a fetch is truncated, the partial record is dropped and the next poll starts from the beginning of that same record [`consumer.js:139-142`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/consumer.js#L139-L142).
* In `Connection.#onData`, if a response's `correlationId` does not match the `correlationId` of the oldest pending request, the client rejects the request with a `ProtocolError` rather than attempting to find the correct match [`connection.js:120-122`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/node/src/connection.js#L120-L122).
