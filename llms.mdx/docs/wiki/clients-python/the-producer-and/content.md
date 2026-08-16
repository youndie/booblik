# The `Producer` and `Topic` interaction (/wiki/clients-python/the-producer-and)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## What this module is responsible for [#what-this-module-is-responsible-for]

The interaction between the `Producer` and `Topic` handles the routing of data from the application layer to the specific partitions of a broker. It manages the complexity of partition selection via hashing or round-robin logic, the grouping of records into efficient batches, and the lifecycle of the underlying network connection.

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant App
    participant Producer
    participant TopicHandle
    participant Connection
    participant Broker

    App->>TopicHandle: send(record, key)
    alt key is null
        TopicHandle->>TopicHandle: increment round-robin
    else key is present
        TopicHandle->>TopicHandle: partitionFor(key)
    end
    TopicHandle->>Producer: produce(topic, partition, record)
    Producer->>Connection: sendall(request)
    Connection->>Broker: wire.encode_produce(...)
    Broker-->>Connection: wire.decode_produce(...)
    Connection-->>Producer: ProduceResult
    Producer-->>App: CompletableDeferred<Offset>"
/>

## TopicHandle and Partition Selection [#topichandle-and-partition-selection]

The `TopicHandle` acts as a high-level abstraction that simplifies publishing by remembering the topic name and its available partitions. When a record is sent, the handle must decide which partition will receive it. If a `key` is provided, the handle uses a `Partitioner` to hash the key into a specific partition index ([`Publishing.kt:40-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L40-L45)). If the key is `null`, the handle employs a round-robin strategy where it selects the next partition in sequence to ensure an even spread of records ([`Publishing.kt:25-34`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L25-L34)). This round-robin counter is incremented on every call, meaning that even asking for a partition and then sending a record can result in two turns of the counter ([`README.md:204-207`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L204-L207)).

## The BatchScope and Contiguous Writes [#the-batchscope-and-contiguous-writes]

To maximize throughput, the `batch` function allows users to group multiple records into a single request. This is achieved through a `BatchScope` which collects records into a list ([`Publishing.kt:55-65`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L55-L65)). Crucially, this mechanism bypasses the standard producer accumulator to ensure that records land **contiguously** in the partition log. This means that a single request results in a sequence of offsets with nothing interleaved between them, allowing a group of records to be identified by their starting offset ([`Publishing.kt:72-75`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L72-L75)).

## AckPolicy.NONE and the Absence of Offsets [#ackpolicynone-and-the-absence-of-offsets]

The `AckPolicy` determines how the client waits for confirmation from the broker. When `AckPolicy.NONE` is selected, the client performs a "fire and forget" operation. In this mode, the `produce` method simply sends the request to the socket and returns `null` immediately ([`connection.py:109-111`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L109-L111)). Because the client does not wait for a response from the broker, no offsets are returned to the caller, and there is no way to verify if the records were actually written or if the broker dropped them ([`README.md:58-61`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L58-L61)).

## The Producer-Connection Ownership Model [#the-producer-connection-ownership-model]

A `Connection` is a synchronous, blocking socket that is **not safe for concurrent use** because requests and responses are matched by a correlation ID ([`connection.py:26-28`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L26-L28)). To prevent threads from reading each other's answers, a `Producer` is designed to own its own `Connection` ([`README.md:54-57`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L54-L57)). This ownership ensures that the producer is the only writer to that specific socket, maintaining the integrity of the request-response sequence.

## Partial Batch Survival and Recovery [#partial-batch-survival-and-recovery]

While batches are intended to be contiguous, they are not atomic. If a crash occurs during a write, the broker's recovery mechanism ensures that the log remains consistent by keeping the prefix of the batch that passed its checksums, but it will stop at the first record that fails ([`connection.py:97-98`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L97-L98)). This means a partial batch can survive on its own as a valid prefix in the log, even if the full batch was not successfully committed ([`Publishing.kt:76-79`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L76-L79)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                                       | Lines     | What is there                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------- | ------------------------------------------------ |
| [`…/booblik/connection.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L23-L40 "clients/python/booblik/connection.py")                                                                                          | `23-40`   | The `Connection` class and its initialization.   |
| [`…/booblik/partition.py`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/partition.py#L19-L32 "clients/python/booblik/partition.py")                                                                                             | `19-32`   | The `fnv1a32` hashing implementation.            |
| [`…/net/PublishingTest.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PublishingTest.kt#L36-L43 "booblik-net/src/test/kotlin/ru/workinprogress/booblik/net/PublishingTest.kt")            | `36-43`   | Tests for key-based partition consistency.       |
| [`…/client/Publishing.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L19-L24 "booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt") | `19-24`   | The `TopicHandle` class definition.              |
| [`dev/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L204-L207 "dev/README.md")                                                                                                                                                | `204-207` | Explanation of round-robin behavior.             |
| [`…/java/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/java/README.md#L54-L57 "clients/java/README.md")                                                                                                                             | `54-57`   | Details on `Producer` ownership of `Connection`. |

## Behaviour that surprises [#behaviour-that-surprises]

* **`partitionFor(null)`** advances a counter even when just querying; if you call it to "peek" at a partition and then call it again to send, you will skip a partition ([`README.md:204-207`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/README.md#L204-L207)).
* **`AckPolicy.NONE`** returns `null` in Python, which can be interpreted as a lack of response rather than an empty response, as no offset exists until the broker processes the write ([`connection.py:109-111`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/python/booblik/connection.py#L109-L111)).
* **`TopicHandle`** partitions are determined by asking the broker via metadata rather than being passed as a fixed count, preventing mismatches between client configuration and broker state ([`Publishing.kt:14-17`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/booblik-client/src/main/kotlin/ru/workinprogress/booblik/net/client/Publishing.kt#L14-L17)).
