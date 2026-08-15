# booblik for Java

A client for [booblik](../../README.md) — PRODUCE, FETCH and METADATA, publishing and reading
alike.

**No dependencies.** One jar, `java.base`, nothing else. Targets Java 17.

```java
try (Connection connection = Connection.open("localhost:9092")) {
    Connection.Topic topic = connection.topic("orders");   // partitions come from the broker
    topic.send(payload, key, AckPolicy.WRITTEN);
}
```

## Why this is not a wrapper over the Kotlin client

booblik already has a JVM client, in Kotlin. A facade over it was written first and then thrown
away, for a reason worth recording.

The **syntax** problem is real and worse than it sounds: Kotlin mangles the names of functions with
`value class` parameters, so that client's own ABI dump lists `topic-7zVQyJo`, `produce-iPAU26k` and
`batch-32amf5I`. A hyphen is not a legal character in a Java identifier, which makes those methods
not awkward from Java but **uncallable**. Every suspending function also compiles to one taking a
`Continuation`.

A facade fixes all of that — and leaves the actual objection standing. It would still put
`kotlin-stdlib` (1.8 MB) and `kotlinx-coroutines-core` (1.5 MB) on the classpath of a service that
chose Java precisely to avoid them. The audience for a Java client is people who do not want the
Kotlin runtime; handing it to them with a nicer syntax is solving the wrong half.

So this is a fifth from-scratch implementation, like Go, Python, Node and .NET, and it is held to
the same [conformance kit](../../conformance).

## Batch, or lose most of the broker

One record per request is the most expensive mistake available here: the broker's own measurements
put batches of a hundred at **4 335 482 records/s against 80 592** one at a time.

Batch by hand when the records are already together:

```java
ProduceResult result = connection.produce("orders", partition, records, AckPolicy.WRITTEN);
// they land contiguously from result.baseOffset()
```

or let a `Producer` do it when they arrive one at a time:

```java
try (Producer producer = new Producer(connection, ProducerConfig.defaults())) {
    CompletableFuture<Long> offset = producer.send("orders", partition, payload);
}
```

A `Producer` **owns its `Connection`**: one thread holds the pending records and is the only writer
to that socket. Do not use the same `Connection` directly while a `Producer` has it — responses are
matched in order, and a second writer takes somebody else's answer. `close()` flushes what is
queued; dropping it would make every clean shutdown a silent data loss.

## Three things that are not obvious

- **`AckPolicy.NONE` answers nothing at all** — not an empty response, nothing, because no offset
  exists until the writer reaches the batch. `produce` returns `null` and a `Producer` completes
  with `Producer.OFFSET_UNKNOWN`. It is also the only mode where the broker may drop an accepted
  record silently;
- **the key never reaches the broker.** The record format has no room for one, so this client picks
  the partition and sends the number. That is why the partitioner is pinned by
  [golden vectors](../../conformance) computed in another language: two publishers that disagree
  put one key in two partitions, and nothing errors when they do;
- **a refusal keeps the connection.** `BrokerException` is a result, not an outage — framing was
  intact, so the broker understood the request and declined it.

`partitionFor(null)` advances a round-robin counter, so asking and then sending is two turns of it.
With a key there is no such thing: the answer is a pure function of the key.

**Java's own trap is the signed byte.** `0x80` must enter the hash as 128, not −128, so the
partitioner masks with `& 0xFF` — the same line Kotlin needs a mask on, Python needs a mask on for
the opposite reason, and JavaScript needs `Math.imul` for.

## Failures are unchecked

`BrokerException` for a refusal, `ProtocolException` for bytes that make no sense,
`UncheckedIOException` for a socket that dropped. A checked `IOException` on every call would tax
every caller for a case most handle in one place if at all.

## Reading

```java
Consumer consumer = connection.consumer("orders", 0, offset);

for (byte[] record : consumer) {
    handle(record);
}
persist(consumer.position());
```

`Iterable<byte[]>`, so the loop belongs to the caller: `break` works, `return` works, and an
exception lands in the caller's own handler. The Kotlin client offers a `Flow` instead, which is the
same idea with suspension — and is exactly the part that could not be exposed to Java, which is why
this client exists at all.

**The loop does not end.** `hasNext()` is always true and blocks until there is something to return,
because a partition has no end — only a place it has not been written to yet.

**The position lives in this client.** `position()` is the number to persist, and persisting it
*after* the records are dealt with is what makes a restart re-deliver rather than skip.

Java is the exception among the five reimplementations on the checksum: `java.util.zip.CRC32C` has
been in the JDK since 9, so there are no forty lines here. **It is one line away from
`java.util.zip.CRC32`** in the same package, a different polynomial under the same name, and a client
using it rejects every record it reads.

The rest is what every reader gets wrong: an empty list is a caught-up consumer and not the end of
the log (`maxWaitMillis`, 5 s by default, keeps that from being a busy loop — and the socket timeout
has to exceed it); a response can stop inside a record because `maxBytes` cuts on a byte boundary,
and the fragment is dropped; and a record bigger than `maxBytes` never arrives at all, which is
`RecordExceedsMaxBytesException` with both numbers rather than a silent stall.

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2 — always v2, including when nothing is being waited for, so
the waiting fields are not exercised only in the branch nobody debugs. Declares
`producer,consumer`.

## Checks

    ./clients/java/gate.sh                                        # gradle test
    ./conformance/run.sh clients/java/conformance-client.sh        # against a real broker

`-Xlint:all -Werror` is on for every compilation, so the build **is** the style and analysis check
and cannot drift out of sync with it. The unit tests need no Docker and no network: they run against
a fake broker that **decodes what this client encoded**, so an encoding mistake fails there rather
than becoming a mystery later.

Both are in `./ci/gate.sh java`.
