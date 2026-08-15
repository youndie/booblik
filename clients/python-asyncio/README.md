# booblik for Python, asyncio

The asyncio client — publishing and reading. **The sources are not in this directory** — they are
[`booblik/aio.py`](../python/booblik/aio.py) in the same package as the synchronous client, because
this is another API over one package rather than another package. What lives here is the two files
`ci/gate.sh` looks for, the same arrangement [`kotlin-native/`](../kotlin-native/README.md) uses.

    pip install booblik

```python
import booblik.aio

async with await booblik.aio.Connection.connect("localhost:9092") as connection:
    topic = await connection.topic("orders")
    await topic.send(payload, key=key)
```

Still no dependencies: `asyncio` is the standard library.

## Why both, and which to reach for

A separate thing from the synchronous client, not a replacement: **a synchronous client can be
driven from asynchronous code on a thread, and an asynchronous one cannot be used from synchronous
code at all.** Reach for this one only if the caller already has an event loop.

They share [`booblik/wire.py`](../python/booblik/wire.py) and nothing else. Splitting the codec out
was the first half of this work, and the seam is the same one the Kotlin/Native client is built on:
**building bytes and moving bytes are different jobs**, and only the second has anything to say
about who is waiting. Without the split this would have been a second copy of the codec, drifting at
exactly the rate nobody notices.

## The accumulator, and one thing it does not prove

```python
async with booblik.aio.Producer(connection) as producer:
    future = await producer.send("orders", partition, payload)
    offset = await future
```

The loop keeps its pending queue getter across iterations instead of writing
`asyncio.wait_for(queue.get(), timeout)`. That shape guards against `wait_for` discarding a result
that arrives exactly as its cancellation lands — which in this loop would be a silent loss, the
caller awaiting an offset for ever while everybody else is served. It is the same failure the JVM
client had twice.

**Measured, not assumed:** swapping in the `wait_for` spelling passed the three-hundred-round
regression five times over. `asyncio.Queue.get` handles its own cancellation and the historical
hazard is closed in CPython. The shape stays as cheap insurance against a guarantee that belongs to
somebody else's implementation — and it is written down as insurance rather than as a bug that was
reproduced, because it was not.

## Reading

```python
consumer = connection.consumer("orders", 0, offset)

async for record in consumer:
    await handle(record)
persist(consumer.position)
```

An async generator, so `await` in the loop body is back-pressure: the next fetch does not happen
until the body is done. A queue would read ahead of what has been handled, which moves the position
past records nobody has processed, and would have nowhere to put the error that ended it.

Cancel the task to stop; the cancellation comes out of the `await` inside as `CancelledError`.

**One thing is easier here than in the synchronous client**: a stream reader has no deadline of its
own, so a long fetch simply waits and a caller who wants to give up wraps the call in
`asyncio.timeout`. The synchronous client has to compare the wait against the socket timeout,
because there the two are the same object.

Everything else — the checksum, the truncated tail, the record that never fits — is
`booblik.wire`, shared with the synchronous client and described in [its README](../python/README.md).

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2. Declares `producer,consumer` to the conformance kit as
`python-asyncio`, and is checked separately from the synchronous client for the same reason it
exists separately: it is a different way of moving the same bytes.

## Checks

    ./clients/python-asyncio/gate.sh                                        # the asyncio tests only
    ./conformance/run.sh clients/python-asyncio/conformance-client.sh        # against a real broker

The gate runs only `tests/test_aio.py`: the synchronous tests belong to `clients/python`, and
running them under both names would make a red run ambiguous about which client broke. The
conformance run is separate from the synchronous one because this is a **different socket
implementation** answering the same contract — the kit is the only thing that can tell.

Both are in `./ci/gate.sh python-asyncio`.
