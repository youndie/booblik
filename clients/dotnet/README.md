# booblik for .NET

A client for [booblik](../../README.md) — PRODUCE, FETCH and METADATA, publishing and reading
alike.

    dotnet add package Booblik

Targets `net8.0`. **The library references no packages** — the whole client is `System.Net.Sockets`
and `System.Buffers.Binary`.

```csharp
using Booblik;

using var connection = await Connection.ConnectAsync("localhost:9092");

var topic = await connection.TopicAsync("orders");   // partitions come from the broker
await topic.SendAsync(payload, key);
```

## Batch, or lose most of the broker

One record per request is the most expensive mistake available here: the broker's own measurements
put batches of a hundred at **4 335 482 records/s against 80 592** one at a time. That is a larger
factor than every other decision in the project put together.

Batch by hand when the records are already together:

```csharp
var result = await connection.ProduceAsync("orders", partition, records);
// they land contiguously from result.Value.BaseOffset
```

or let a `Producer` do it when they arrive one at a time:

```csharp
await using var producer = new Producer(connection, new ProducerConfig
{
    Linger = TimeSpan.FromMilliseconds(5),
});

var offset = await producer.SendAsync("orders", partition, payload);
```

A `Producer` **owns its `Connection`**: one loop holds the pending records and is the only writer to
that socket. Do not use the same `Connection` directly while a `Producer` has it — responses are
matched in order, and a second writer takes somebody else's answer. `DisposeAsync` flushes what is
queued; dropping it would make every clean shutdown a silent data loss.

## Three things that are not obvious

- **`AckPolicy.None` answers nothing at all** — not an empty response, nothing, because no offset
  exists until the writer reaches the batch. `ProduceAsync` returns `null` and a `Producer` completes
  with `Producer.OffsetUnknown`. It is also the only mode where the broker may drop an accepted
  record silently;
- **the key never reaches the broker.** The record format has no room for one, so this client picks
  the partition and sends the number. That is why the partitioner is pinned by
  [golden vectors](../../conformance) computed in another language: two publishers that disagree put
  one key in two partitions, and nothing errors when they do;
- **a refusal keeps the connection.** `BrokerException` is a result, not an outage — framing was
  intact, so the broker understood the request and declined it.

`PartitionFor(null)` advances a round-robin counter, so asking and then sending is two turns of it.
With a key there is no such thing: the answer is a pure function of the key.

**This language's own trap is `checked`.** FNV-1a is defined in arithmetic that wraps at 32 bits, so
`Partitioner` says `unchecked` out loud rather than relying on it being the default — a project
built with `CheckForOverflowUnderflow` would otherwise throw on the second byte of almost any key.

## Reading

```csharp
var consumer = connection.CreateConsumer("orders", 0, offset);

await foreach (var record in consumer.RecordsAsync(token))
{
    await HandleAsync(record);
}
Persist(consumer.Position);
```

`IAsyncEnumerable` and not an event: `await foreach` is back-pressure by construction — the next
fetch does not happen until the body of the loop is done — and an exception lands in the caller's
own handler. An event would push records at whatever rate they arrive, so the position would run
ahead of what has actually been processed.

**The loop does not end.** A partition has no end, only a place it has not been written to yet;
cancel the token to stop.

**The position lives in this client.** `Position` is the number to persist, and persisting it
*after* the records are dealt with is what makes a restart re-deliver rather than skip.

Three things a reader gets wrong:

- **the wrong CRC32.** The sum is CRC-32C (Castagnoli); `System.IO.Hashing.Crc32` is a different
  polynomial with the same name — and a NuGet package besides, where this library takes none. The
  forty lines are in `Crc32C.cs`;
- **an empty list is not the end of the log**, it is what a caught-up consumer gets. `MaxWaitMillis`
  (5 s by default) keeps that from becoming a busy loop;
- **a record bigger than `MaxBytes` never arrives whole**, which is the one stall that does not
  resolve itself: `RecordExceedsMaxBytesException` carries both numbers.

A response can also stop inside a record, because `MaxBytes` cuts on a byte boundary; the fragment
is dropped and `Position` stops before it, so the next poll asks for that record from its start.

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2 — always v2, including when nothing is being waited for, so
the waiting fields are not exercised only in the branch nobody debugs. Declares
`producer,consumer`.

## Checks

    ./clients/dotnet/gate.sh                                       # dotnet test
    ./conformance/run.sh clients/dotnet/conformance-client.sh       # against a real broker

Warnings are errors in both projects, so the gate is the analysis and style check as well as the
compile — there is no separate linter to drift out of sync with it. The unit tests need no Docker
and no network: they run against a fake broker that **decodes what this client encoded**, so an
encoding mistake fails there rather than becoming a mystery later. The conformance run is what
catches a client that is wrong self-consistently, which unit tests by construction cannot.

Both are in `./ci/gate.sh dotnet`.
