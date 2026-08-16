# Producer and Consumer Mechanics (/wiki/clients/producer-and-consumer)



<Callout type="info" title="Generated page">
  Model `gemma-mtp`, commit `ef58254ca7be`, 2026-08-16, sources: 6. Edit the code or the hand-written documentation instead.
</Callout>

## Diagram [#diagram]

<Mermaid
  chart="sequenceDiagram
    participant P as Producer
    participant C as Connection
    participant B as Broker
    participant L as Log/Topic

    P->>C: SendAsync (with Linger)
    Note over P,C: Producer owns Connection
    C->>B: Batch Request
    B->>B: Append to Log
    B-->>C: Response (Offset)
    C-->>P: Await Completion
    P->>L: Commit/Persist Position (Consumer)"
/>

## The Producer and the Connection Ownership [#the-producer-and-the-connection-ownership]

A `Producer` maintains exclusive ownership of its `Connection` to ensure that responses are matched correctly to requests in the order they were sent ([`README.md:44-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L44-L45)). Because a second writer on the same socket would take someone else's answer, the `Producer` is the sole writer to that socket ([`README.md:44-45`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L44-L45)). It is critical to use `DisposeAsync` to flush queued records; failing to do so results in silent data loss during shutdown ([`README.md:46-47`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L46-L47)).

## Batching Strategies: Manual vs Producer [#batching-strategies-manual-vs-producer]

Performance is heavily dependent on batching, as single-record requests are significantly less efficient than batches of a hundred ([`README.md:22-23`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L22-L23)).

| Strategy      | Method         | Mechanism                                                                                                                                                                                                                                           |
| ------------- | -------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Manual**    | `ProduceAsync` | The user provides records already grouped together; they land contiguously from `result.Value.BaseOffset` ([`README.md:29-30`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L29-L30)). |
| **Automatic** | `Producer`     | Uses a `Linger` configuration (e.g., 5ms) to collect records arriving one at a time into a batch ([`README.md:36-42`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L36-L42)).          |

## AckPolicy.None and the Unknown Offset [#ackpolicynone-and-the-unknown-offset]

When using `AckPolicy.None`, the broker does not return an empty response, but rather nothing at all, because no offset exists until the writer reaches the batch ([`README.md:51-52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L51-L52)). In this mode:

* `ProduceAsync` returns `null` ([`README.md:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L52)).
* A `Producer` completes with `Producer.OffsetUnknown` ([`README.md:52`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L52)).
* The broker may drop an accepted record silently ([`README.md:53-54`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L53-L54)).

## Consumer Position and IAsyncEnumerable Back-pressure [#consumer-position-and-iasyncenumerable-back-pressure]

Reading is implemented via `IAsyncEnumerable`, which provides back-pressure by construction: the next fetch does not occur until the current loop body is finished ([`README.md:81-82`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L81-L82)). The `Position` property represents the number to be persisted; it is the responsibility of the caller to persist this *after* records are dealt with to ensure a restart re-delivers rather than skips data ([`README.md:89-90`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L89-L90)).

## Record Fragmentation and MaxBytes [#record-fragmentation-and-maxbytes]

A consumer may encounter a stall if a record is larger than `MaxBytes`. In such cases, the record is never delivered whole, and a `RecordExceedsMaxBytesException` is thrown ([`README.md:100`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L100)). Additionally, a response can stop inside a record if `MaxBytes` cuts on a byte boundary; the fragment is dropped, and the `Position` stops before it, requiring the next poll to request that record from its start ([`README.md:102-103`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L102-L103)).

## The Claims Log and Worker Coordination [#the-claims-log-and-worker-coordination]

In a task queue built on top of the log, the order of a specific partition acts as the arbiter for task acquisition ([`Main.kt:39-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L39-L40)). Workers write claims into a `claims` topic, and the first claim on a free task wins ([`Main.kt:39-40`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L39-L40)). This process involves a round-trip cost: a worker must write the claim and then read the log until the claim comes back to settle the task ([`Main.kt:187-188`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L187-L188)).

## The Write Actor and Group Commit [#the-write-actor-and-group-commit]

High-throughput durable writes are achieved through group commits, where a single barrier (operation) can service multiple producers ([`benchmarking.md:263-264`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/benchmarking.md#L263-L264)). While a single producer is limited by the frequency of barriers (approx. 250/s), 64 producers can achieve over 7,500 durable writes per second by sharing the same disk barrier ([`benchmarking.md:270-273`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/benchmarking.md#L270-L273)).

## Key files [#key-files]

| File                                                                                                                                                                                                                                                                        | Lines     | What is there                                     |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------- | ------------------------------------------------- |
| [`…/dotnet/README.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L14-L18 "clients/dotnet/README.md")                                                                                                        | `14-18`   | Usage example for `Connection` and `TopicAsync`   |
| [`…/queue/Main.kt`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt#L157-L161 "dev/queue-worker/src/main/kotlin/ru/workinprogress/booblik/dev/queue/Main.kt") | `157-161` | Producer initialization and scope setup           |
| [`docs/benchmarking.md`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/docs/benchmarking.md#L243-L245 "docs/benchmarking.md")                                                                                                            | `243-245` | Performance data for `WRITTEN` vs `NONE` policies |

## Behaviour that surprises [#behaviour-that-surprises]

* `AckPolicy.None` does not return an empty response; it returns nothing, making it impossible to know if a record was accepted ([`README.md:51`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L51)).
* The `key` is not sent to the broker; the client uses the key to pick a partition and sends only the partition number ([`README.md:55-56`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L55-L56)).
* `BrokerException` is a result of a declined request (framing was intact), not a connection outage ([`README.md:59`](https://github.com/youndie/booblik/blob/ef58254ca7be0c2e8c83b5ee75d4ce32647cf800/clients/dotnet/README.md#L59)).
