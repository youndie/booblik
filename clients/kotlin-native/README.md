# booblik for Kotlin/Native

A client for [booblik](../../README.md) on `linuxX64` and `macosArm64` — publishing and reading.

**The sources are not in this directory.** They are [`booblik-native`](../../booblik-native) in the
main Gradle build, and that is the point of the milestone rather than an accident of layout: this
client is a **target of the shared protocol**, not a fifth reimplementation of it. The codec, the
ids and the partitioner all come from [`booblik-protocol`](../../booblik-protocol), which compiles
for the JVM and for both native targets from one source.

What lives here is the two files `ci/gate.sh` looks for — `gate.sh` and `conformance-client.sh` —
so a Kotlin/Native client is checked exactly like a Go or a Python one.

```kotlin
dependencies {
    implementation("io.github.youndie.booblik:booblik-native:0.3.1")
}
```

```kotlin
BooblikConnection("localhost:9092").use { connection ->
    val topic = connection.topic(TopicName("orders"))       // partitions come from the broker
    topic.send(payload, key = key)
}
```

## Synchronous, and no accumulator yet

Blocking POSIX sockets, no dependencies, no coroutines. The JVM client's accumulator is a coroutine
holding a mailbox; the equivalent here needs a concurrency story on Native decided on its own
evidence, so it is **M-134а** rather than something improvised.

Batching is not missing in the meantime — `produce` takes a list, which is what a batch is:

```kotlin
val result = connection.produce(topic, partition, records)
// they land contiguously from result.baseOffset
```

Use it. One record per request throws away the largest single performance factor there is: the
broker's own measurements put batches of a hundred at **4 335 482 records/s against 80 592** one at
a time.

## Reading

```kotlin
val consumer = connection.consumer(TopicName("orders"), PartitionId(0), offset)

for (record in consumer.records()) {
    handle(record)
}
persist(consumer.position)
```

A `Sequence` and not a `Flow`, because there is nothing here to suspend on: the socket is a blocking
POSIX one, so a `Flow` over it would suspend nothing and only hide which thread is stuck in `recv`.
Give the consumer a thread of its own.

**The loop does not end.** A partition has no end, only a place it has not been written to yet.
`poll()` is the same thing one fetch at a time.

**The position lives in this client.** `position` is the number to persist, and persisting it
*after* the records are dealt with is what makes a restart re-deliver rather than skip.

## Where decision Р8's last objection went

Р8 rejected multiplatform for three reasons; M-134 re-examined all of them. Two were already gone —
there are native consumers now (tracy, shildik, mongkn, hub-backend, the stocker bot), and the "no
TLS on Native" objection never applied because booblik has no TLS at all, TLS being incompatible
with the zero-copy read path by construction.

The third stood until M-138: **verifying a record's checksum is the client's job**, because on the
zero-copy path the broker never touches those bytes, and `CRC32C` on the JVM is an intrinsic
compiling to a single instruction. A hand-written loop in common code would have cost every byte a
JVM consumer reads.

It turned out to be one function. The checksum is `expect`/`actual`: the JVM keeps its intrinsic,
Kotlin/Native pays for a 256-entry table, and the FETCH decoder itself is shared in
`:booblik-protocol` with the rest of the codec. This client declares `producer,consumer` and answers
all fourteen checks.

**The table caught its own bug on the first run.** The reflected polynomial was written as a
hand-derived negative literal, `-0x7D644AC8`, which is `0x829BB538` — a different polynomial
producing a stable, plausible, everywhere-wrong sum. The golden vectors failed immediately; nothing
else would have, because both implementations were self-consistent and only one was right.

## Three things that are not obvious

- **`AckPolicy.NONE` answers nothing at all** — not an empty response, nothing, because no offset
  exists until the writer reaches the batch. `produce` returns null;
- **the key never reaches the broker.** The record format has no room for one, so the client picks
  the partition and sends the number. The partitioner is pinned by
  [golden vectors](../../conformance) computed in another language, and checked here on **every**
  target — the JVM one alone could not have said the native build agrees;
- **`partitionFor(null)` advances a round-robin counter**, so asking and then sending is two turns
  of it. With a key there is no such thing: the answer is a pure function of the key.

## Checks

    ./clients/kotlin-native/gate.sh                                       # native tests, this host's target
    ./conformance/run.sh clients/kotlin-native/conformance-client.sh      # against a real broker

`gate.sh` picks the target the host can actually **run**: a `linuxX64` test binary does not execute
on macOS and a `macosArm64` one does not execute on Linux, so "the native code is tested" is a
different Gradle task on each machine. On a host with neither it exits 77 — skipped, not green.

Both are in `./ci/gate.sh kotlin-native`.
