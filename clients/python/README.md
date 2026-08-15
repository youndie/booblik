# booblik for Python

A client for [booblik](../../README.md) — PRODUCE, FETCH and METADATA, publishing and reading
alike.

    pip install booblik

**No dependencies**, tests included. The client is `socket` and `struct`; the tests are `unittest`.

```python
from booblik import Connection, AckPolicy

with Connection.connect("localhost:9092") as connection:
    topic = connection.topic("orders")          # partitions come from the broker
    topic.send(payload, key=key, ack=AckPolicy.WRITTEN)
```

Synchronous, deliberately. A synchronous client can be driven from asynchronous code on a thread; an
asynchronous one cannot be used from synchronous code without an event loop, and most Python that
publishes events is a script, a task worker or a request handler.

There **is** an asyncio client now — [`booblik.aio`](../python-asyncio/README.md), added as a
separate thing rather than a rewrite of this one. They share `booblik/wire.py`, which is the codec:
bytes in, bytes out, with nothing to say about who is waiting.

## Batch, or lose most of the broker

One record per request is the most expensive mistake available here: the broker's own measurements
put batches of a hundred at **4 335 482 records/s against 80 592** one at a time. That is a larger
factor than every other decision in the project put together.

Batch by hand when the records are already together:

```python
result = connection.produce("orders", partition, records)
# they land contiguously from result.base_offset
```

or let a `Producer` do it when they arrive one at a time:

```python
from booblik import Producer, ProducerConfig

with Producer(connection, ProducerConfig(linger=0.005)) as producer:
    future = producer.send("orders", partition, payload)
    offset = future.result(timeout=30)          # a concurrent.futures.Future
```

A `Producer` **owns its `Connection`**: one thread holds the pending records and is the only writer
to that socket. Do not use the same `Connection` directly while a `Producer` has it — responses are
matched in order, and a second writer takes somebody else's answer.

## Three things that are not obvious

- **`AckPolicy.NONE` answers nothing at all** — not an empty response, nothing, because no offset
  exists until the writer reaches the batch. `produce` returns `None` and a future completes with
  `OFFSET_UNKNOWN`. It is also the only mode where the broker may drop an accepted record silently;
- **the key never reaches the broker.** The record format has no room for one, so this client picks
  the partition and sends the number. That is why the partitioner is pinned by
  [golden vectors](../../conformance) computed in another language: two publishers that disagree
  put one key in two partitions, and nothing errors when they do;
- **a refusal keeps the connection.** `BrokerError` is a result, not an outage — framing was intact,
  so the broker understood the request and declined it.

`partition_for(None)` advances a round-robin counter, so asking and then sending is two turns of it.
With a key there is no such thing: the answer is a pure function of the key.

**Python's own trap is the mask.** Its integers do not overflow, so FNV-1a without `& 0xFFFFFFFF`
computes an ever-growing number that agrees with no other language. That is this client's version of
the signed-byte cast Java and Kotlin need, and the vectors catch both.

## Reading

```python
consumer = connection.consumer("orders", 0, offset)   # the offset is yours to keep

for record in consumer:
    handle(record)
persist(consumer.position)                            # where to resume
```

A generator, so the loop belongs to the caller: `break` works, `return` works, the exception handler
is yours, and nothing is fetched until the first record is asked for. `poll()` is the same thing one
fetch at a time, when the loop belongs to somebody else.

**The loop does not end.** A partition has no end, only a place it has not been written to yet.

**The position lives in this client.** There are no consumer groups, no coordinator and no committed
offsets in this broker — an offset is a number the reader already knows, and asking a broker to
remember it is what drags in cluster consensus. `consumer.position` is the number to write down, and
writing it down *after* the records are dealt with rather than before is what makes a restart
re-deliver instead of skip.

Four things a reader gets wrong:

- **the wrong CRC32.** The sum is CRC-32C (Castagnoli). `zlib.crc32` is a different polynomial with
  the same name, so this client carries its own forty lines in `booblik/crc32c.py` rather than a
  dependency — a checksum that is optional to install is a checksum that is optional;
- **an empty list is not the end of the log**, it is what a consumer that has caught up gets.
  `max_wait_millis` (5 s by default) is what keeps that from becoming a busy loop; the broker answers
  the moment a record lands, not when the timer runs out. **The socket timeout has to exceed it** —
  `Connection.connect(..., timeout=30)` against a five-second wait — and `fetch` refuses the
  combination rather than letting it surface as a `socket.timeout` on a healthy long fetch;
- **a response can stop inside a record**, because `max_bytes` cuts on a byte boundary. The fragment
  is dropped and `position` stops before it;
- **a record bigger than `max_bytes` never arrives whole**, so retrying is the stall.
  `RecordExceedsMaxBytesError` carries both numbers, because raising `max_bytes` is the only fix.

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2 — always v2, including when nothing is being waited for, so
the waiting fields are not exercised only in the branch nobody debugs. Declares
`producer,consumer`.

## Checks

    ./clients/python/gate.sh                                       # unittest, no install needed
    ./conformance/run.sh clients/python/conformance-client.sh      # against a real broker

The unit tests need no Docker and no network: they run against a fake broker that **decodes what
this client encoded**, so an encoding mistake fails there rather than becoming a mystery later. The
conformance run is what catches a client that is wrong self-consistently, which unit tests by
construction cannot.

Both are in `./ci/gate.sh python`.
