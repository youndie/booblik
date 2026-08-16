# clients/python (/wiki/clients-python)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant C as Client (Connection/Producer/Consumer)
    participant W as Wire (Codec)
    participant B as Broker

    C->>W: encode_produce/fetch/metadata
    W->>C: frame(api_key, version, correlation, payload)
    C->>B: sendall(request)
    B-->>C: socket.recv(length)
    C->>W: read_header(frame_bytes, expect)
    W-->>C: decode_produce/fetch/metadata
    C->>C: verify_checksum(crc32c)"
/>

## The `Connection` lifecycle [#the-connection-lifecycle]

The `Connection` class manages a synchronous, blocking TCP socket, ensuring that requests and responses are matched via a unique correlation ID ([`connection.py:34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L34)). It handles the low-level exchange of bytes through `_exchange` ([`connection.py:58`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L58)), which reads a length-prefixed frame and then uses `wire.read_header` to validate the response's correlation ID and error code ([`connection.py:60`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L60)).

## The `Producer` and `Topic` interaction [#the-producer-and-topic-interaction]

Users can publish data using `Producer.send` or via a `Topic` handle, which simplifies routing by managing partition selection ([`connection.py:174`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L174)). When a `None` key is provided, the `Topic.partition_for` method uses a round-robin strategy by incrementing an internal counter ([`connection.py:191-193`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L191-L193)). For non-null keys, the client ensures deterministic routing by hashing the key to a specific partition ([`connection.py:194`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L194)).

More: [The `Producer` and `Topic` interaction](clients-python/the-producer-and)

## The `Consumer` and `Fetched` data stream [#the-consumer-and-fetched-data-stream]

The `Consumer` provides a high-level interface to read records from a specific partition starting from a given offset ([`connection.py:151`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L151)). Data is returned as a `Fetched` object, which includes a `truncated` flag to indicate if a response was cut short because a record exceeded `max_bytes` ([`wire.py:77-82`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L77-L82)). Every record in the stream is verified using `crc32c` to ensure data integrity ([`wire.py:163-164`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L163-L164)).

## The `crc32c` checksum mechanism [#the-crc32c-checksum-mechanism]

The client implements the Castagnoli CRC-32C polynomial (`0x1EDC6F41`) using a pre-computed lookup table for efficiency ([`crc32c.py:31-48`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/crc32c.py#L31-L48)). The implementation uses a reflected polynomial (`0x82F63B78`) and bit-shifting logic to process data without external dependencies ([`crc32c.py:41`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/crc32c.py#L41)).

## The `partition_for` hashing strategy [#the-partition_for-hashing-strategy]

Partitioning is achieved through the `fnv1a32` function, which implements the 32-bit FNV-1a hash algorithm ([`partition.py:19`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/partition.py#L19)). This hash is then "folded" into the available partition range using a modulo operation ([`partition.py:44`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/partition.py#L44)).

## Error handling and `BrokerError` codes [#error-handling-and-brokererror-codes]

Errors are categorized into protocol-level failures and broker-side refusals. Broker refusals are represented by the `Code` enumeration ([`errors.py:6-12`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/errors.py#L6-L12)):

| Code                          | Value | Description                       |
| ----------------------------- | ----- | --------------------------------- |
| NONE                          | 0     | No error                          |
| UNKNOWN\_TOPIC\_OR\_PARTITION | 1     | Topic or partition does not exist |
| OFFSET\_OUT\_OF\_RANGE        | 2     | Requested offset is invalid       |
| RECORD\_TOO\_LARGE            | 3     | Record exceeds broker limits      |
| UNSUPPORTED\_VERSION          | 4     | Protocol version mismatch         |
| CORRUPT\_REQUEST              | 5     | The request is malformed          |

## Key files [#key-files]

| File                                                                                                                                                                                              | Lines   | What is there                                |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------- |
| [`…/booblik/errors.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/errors.py#L6-L12 "clients/python/booblik/errors.py")              | `6-12`  | The `Code` enumeration for broker refusals   |
| [`…/booblik/partition.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/partition.py#L19-L32 "clients/python/booblik/partition.py")    | `19-32` | The `fnv1a32` hashing implementation         |
| [`…/booblik/crc32c.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/crc32c.py#L31-L43 "clients/python/booblik/crc32c.py")             | `31-43` | The CRC-32C polynomial and table generation  |
| [`…/booblik/connection.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L23-L40 "clients/python/booblik/connection.py") | `23-40` | The `Connection` class for socket management |
| [`…/booblik/wire.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L88-L91 "clients/python/booblik/wire.py")                   | `88-91` | The `frame` function for encoding requests   |

## Behaviour that does not surprise [#behaviour-that-does-not-surprise]

* `Connection.fetch` will raise a `ValueError` if the requested `max_wait_millis` is greater than or equal to the socket's timeout, preventing the client to hang while the broker is legitimately waiting ([`connection.py:139-143`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L139-L143)).
* `decode_fetch` will raise a `CorruptRecordError` if the computed checksum of a record does not match the `stored` checksum provided in the header ([`wire.py:164-165`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/wire.py#L164-L165)).
* `Topic.partition_for` will raise a `ValueError` if the number of partitions provided is not at least one ([`partition.py:43`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/partition.py#L43)).
