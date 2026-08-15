# booblik for Node

A client for [booblik](../../README.md) — PRODUCE, FETCH and METADATA, publishing and reading alike.

    npm install booblik

**No dependencies**, tests included. The client is `node:net` and `Buffer`; the tests are
`node:test`. ESM only, Node 20 or newer.

```js
import { AckPolicy, Connection, Producer } from "booblik";

const connection = await Connection.connect("localhost:9092");
const topic = await connection.topic("orders");     // partitions come from the broker
await topic.send(payload, key, AckPolicy.WRITTEN);
```

Offsets come back as numbers rather than `BigInt`. A log would need more than 2^53 records for that
to lose precision, which is not a quantity this broker can reach, and `BigInt` everywhere would be a
tax on every caller for a limit nobody meets.

## Batch, or lose most of the broker

One record per request is the most expensive mistake available here: the broker's own measurements
put batches of a hundred at **4 335 482 records/s against 80 592** one at a time. That is a larger
factor than every other decision in the project put together, so batching is not an optimisation to
enable later — it is what a producer is.

Batch by hand when the records are already together:

```js
const result = await connection.produce("orders", partition, records);
// they land contiguously from result.baseOffset
```

or let a `Producer` do it when they arrive one at a time:

```js
const producer = new Producer(connection);
const offset = await producer.send("orders", partition, payload);
await producer.close();                             // close flushes what is queued
```

A `Producer` **owns its connection**: one loop holds the pending records and is the only writer to
that socket.

## Reading

```js
const consumer = connection.consumer("orders", 0, offset);   // the offset is yours to keep

for await (const record of consumer) {
    await handle(record);
}
persist(consumer.position);                                  // where to resume
```

An async iterator and not an `on("record")` event: `for await` is back-pressure by construction —
the next fetch does not happen until the body of the loop is done — and an error ends the loop where
the caller can catch it. An emitter would push records at whatever rate they arrive with no way for
the handler to say it is not ready, so the position would run ahead of what has actually been
processed. `poll()` is the same thing one fetch at a time.

**The loop does not end.** A partition has no end, only a place it has not been written to yet;
`break` out of it, and the fetching stops with it.

**The position lives in this client.** There are no consumer groups, no coordinator and no committed
offsets in this broker — an offset is a number the reader already knows, and asking a broker to
remember it is what drags in cluster consensus. `consumer.position` is the number to write down, and
writing it down *after* the records are dealt with rather than before is what makes a restart
re-deliver instead of skip.

Reading from the beginning means starting at the partition's `logStartOffset` from `metadata`, not
at zero: zero is `OFFSET_OUT_OF_RANGE` on any topic that has ever dropped a segment to retention.

## Four things a reader gets wrong

- **the wrong CRC32.** The sum is CRC-32C (Castagnoli). `zlib.crc32`, which Node grew in v20.15, is
  a different polynomial with the same name, and a client using it rejects every record it reads.
  The forty lines are in [`src/crc32c.js`](src/crc32c.js) — a package would make verification
  optional to install, and the point of verifying is that it happens everywhere;
- **the sum is unsigned, and JavaScript's bitwise operators are not.** `>>> 0` at the end of the
  checksum is not cosmetic: without it about half of all sums come out negative and match nothing
  the broker ever stored. Same family as `Math.imul` in the partitioner — the arithmetic here is not
  the arithmetic of a double;
- **an empty batch is not the end of the log**, it is what a consumer that has caught up gets.
  `maxWaitMillis` (5 s by default) keeps that from becoming a busy loop: the broker holds the request
  and answers the moment a record lands;
- **a record bigger than `maxBytes` never arrives whole**, so retrying is the stall.
  `RecordExceedsMaxBytesError` carries both numbers, because raising `maxBytes` is the only fix.

A response can also stop inside a record, because `maxBytes` cuts on a byte boundary; the fragment
is dropped and `position` stops before it, so the next poll asks for that record from its start.

## Three things that are not obvious

- **`AckPolicy.NONE` answers nothing at all** — not an empty response, nothing, because no offset
  exists until the writer reaches the batch. `produce` resolves to `null`. It is also the only mode
  where the broker may drop an accepted record without telling anyone;
- **the key never reaches the broker.** The record format has no room for one, so this client picks
  the partition and sends the number. That is why the partitioner is pinned by
  [golden vectors](../../conformance) computed in another language: two publishers that disagree put
  one key in two partitions, and nothing errors when they do;
- **a refusal keeps the connection.** A `BrokerError` is a result, not an outage — framing was
  intact, so the broker understood the request and declined it.

`partitionFor(null)` advances a round-robin counter, so asking and then sending is two turns of it.
With a key there is no such thing: the answer is a pure function of the key.

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2 — always v2, including when nothing is being waited for, so
the waiting fields are not exercised only in the branch nobody debugs. Declares `producer,consumer`.

## Checks

    ./clients/node/gate.sh                                          # node --test
    ./conformance/run.sh clients/node/conformance-client.sh         # against a real broker

`node --test` needs no Docker and no network: the tests run against a fake broker that **decodes
what this client encoded**, so an encoding mistake fails there rather than becoming a mystery later.
The conformance run is what catches a client that is wrong self-consistently, which unit tests by
construction cannot.

Both are in `./ci/gate.sh node`.
