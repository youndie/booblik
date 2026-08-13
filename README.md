# booblik

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![jvm](https://img.shields.io/badge/JVM-25-blue?logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![license](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

A message broker in Kotlin/JVM built on an append-only log: a topic splits into partitions, a
partition into segments, and a consumer reads by a numeric offset it keeps itself.

The niche is not "a Kafka replacement". It is a broker that fits in one head and one process — no
ZooKeeper, no KRaft quorum, no group coordinator. Everything that makes Kafka operable as a cluster
is deliberately absent; everything that makes a log fast is reproduced and **measured**.

## Overview

- **Storage**: segments with a sparse offset index, two write paths (`FileChannel` and an FFM
  memory mapping), rolling, recovery from a torn write, retention by size and by age
- **A per-partition writer actor** that groups whatever is already in its mailbox into one barrier —
  no timer, no configured window: the group size self-adjusts to the load
- **Its own network layer**: a selector loop, one coroutine per connection, a binary protocol, and
  FETCH served straight from the page cache to the socket with `transferTo`
- **Long FETCH** — a caught-up consumer waits on the broker instead of polling it, and a write
  during the wait wakes it immediately
- **A client library**: a pipelined connection matched by correlation id, an accumulating producer,
  a topic handle that routes by key, and subscriptions as `Flow<RecordBatch>`
- **A runnable broker**: configuration validated at startup, a metrics line, retention, a
  distribution, and a container image with its own health check
- **Per-record CRC32C**, verified on every read that touches the bytes — which the zero-copy path,
  by construction, does not

Topic creation is not coming: the set of partitions is fixed at startup on purpose. Neither are TLS
and compression — both are incompatible with zero-copy by construction.

## Performance

Measured on a rented Linux machine (ext4 on a local disk, nothing else running) and, for the
end-to-end figures, on two such machines with a 7.85 Gbit/s link between them — the broker in its
own process, the load generator on the other host.
[docs/benchmarking.md](docs/benchmarking.md) has the methodology and the full tables.

| | |
|---|---|
| PRODUCE ceiling, over the wire | **1,404,750 records/s** (140,475 requests of 10 × 128 B) |
| PRODUCE latency at 100k requests/s | p50 **0.736 ms** |
| FETCH | wire-bound at 396.8 MiB/s — the link, not the broker |
| Zero-copy versus a heap copy | **2.5–2.8× less CPU per GiB served**, identical bytes |
| Segment append, mapped, no flush | 11.1M records/s at 64 B — **13.9×** the `FileChannel` path |
| Segment append, `fsync` per record | 2,769/s against 1,659/s — 1.7× |
| Recovery | **0.30 s per GiB** of log, which is why there is no separate index file |
| Idle traffic of a caught-up consumer | **0.50 requests/s** against 18.45 when polling |

Two of those rows exist because an earlier answer was wrong. "Under durability the two write paths
are indistinguishable" and "the mapped path has a worse worst second" were both **properties of the
stand**, not of the code: the first came from WSL2, where `msync` costs nothing next to the barrier,
the second from APFS, where the spread is 15.4× against ext4's 1.1×. Both are retracted in the
document, next to the numbers that replaced them.

The harness itself cost more than most of what it measured. Running it inside the broker's JVM —
which every end-to-end number before M-37 did — inflated the median by **9.2×** and cost 19 % of the
ceiling, because the two shared a heap, a scheduler and a GC.

## Runtime footprint

booblik targets a deliberately small JVM, and that is a constraint rather than a tuning knob — the
tests and the benchmarks run under it too, so an allocation the hot path is not supposed to make
fails the gate instead of production:

```
-XX:+UseSerialGC -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M
-Xss256k -XX:MaxMetaspaceSize=80M -Xmx64M
```

Measured cost: none worth mentioning — seven of eight benchmark rows match a default JVM within
error. Note that `-Xmx` does **not** bound a mapped segment: `FileChannel.map` is neither heap nor
direct-buffer memory.

The broker prints these arguments at startup, and that is not decoration. The profile is baked into
the distribution's start script, and one `JAVA_OPTS` line in somebody else's `Dockerfile` silently
replaces it — after which what ships is a process nobody measured.

## Run the broker

```bash
docker run -d -p 9092:9092 -e BOOBLIK_TOPICS=orders:3 \
  -v booblik-data:/var/lib/booblik ghcr.io/youndie/booblik:0.1.0
```

Or build it yourself — the script runs the gate, then `installDist`, then `docker build`, in that
order, because the image has no build stage and a bare `docker build .` would package whatever
happens to be sitting in `booblik-app/build/install`:

```bash
./ci/docker-build.sh booblik:local
```

**62 MB to pull**, on `bellsoft/liberica-openjre-alpine:25` — Alpine with **glibc**, not musl, so
the runtime is the one the numbers above were measured on. Unpacked it is 240 MB as Linux docker
reports it and 176 MB as Docker Desktop does, which is a fact about `docker images` rather than
about the image; the ratio is the portable part, and it is **half** of the same distribution on
`eclipse-temurin:25-jre` (514 MB against 240 on the same host). Nothing is installed into the
image — busybox already covers what the start scripts call. Non-root under a pinned uid, data on a
volume, and a `HEALTHCHECK` that asks the broker METADATA rather than opening a TCP connection: the kernel accepts
a connection into the backlog with no help from the process, so a connect cannot tell a live broker
from a hung one.

Without Docker:

```bash
./gradlew :booblik-app:installDist
./booblik-app/build/install/booblik-app/bin/booblik-app broker.properties
```

Configuration is `booblik.*` properties, and every one of them also reads from the environment with
the dots turned into underscores — `booblik.port` is `BOOBLIK_PORT`. An unknown key stops the broker
at startup rather than surfacing at 3am.

## Use the client

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "WipSnapshots"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    implementation("io.github.youndie.booblik:booblik-client:0.1.3")
}
```

Snapshots only for now. The client pulls `booblik-core` and coroutines with it — the API hands out
`Flow` and takes a `CoroutineScope`, so both are compile-scope dependencies rather than details
behind the module.

Publishing, with the accumulator doing the work — the unit of a write is worth 54×, more than any
other decision in this project:

```kotlin
val producer = Producer(BooblikConnection(address, scope), scope)
val orders = producer.topic(TopicName("orders"))

orders.send(payload, key = "user-42".toByteArray())   // the key picks the partition, client-side

producer.batch(orders, PartitionId(0)) {              // one request, contiguous offsets
    +"first".toByteArray()
    +"second".toByteArray()
}
```

Keys never reach the wire: the partition is chosen in the client, so the broker never has to agree
with it about hashing.

Subscribing:

```kotlin
BooblikSubscriber(address).use { subscriber ->
    subscriber.follow(TopicName("orders"), from = StartPosition.Earliest)
        .collect { batch ->
            handle(batch.records)
            println("lag ${batch.lag}")
        }
}
```

`follow()` waits and never completes; `replay()` ends at the high watermark as it was when the flow
started. `StartPosition.Earliest` is the start of the **live** log, not zero — retention moves it.

Back-pressure comes free: a slow collector suspends, and the fetch loop stops asking. That is the
only reason this is a `Flow` and not a callback. It hands over a **batch** rather than a record for
an arithmetic reason — the `Flow` machinery costs 5.5 ns per emission and the `callbackFlow` channel
another 54, against 712 ns of end-to-end path per record; per batch of a hundred that is 0.008 %,
per record it would have been 8 %.

Positions are the consumer's own. `OffsetStore` is declared and deliberately not implemented, and
the word `commit` appears nowhere in its names, because the broker knows nothing about it.

## Internals

| Layer | |
|---|---|
| Segment | `[int32 length][int32 crc32c][payload]`, body first and length prefix **last** — a crash between the two stores leaves a zero where recovery stops, not a plausible header in front of nothing |
| Index | sparse, one entry per N bytes, rebuilt from the segment at startup; a separate file would buy 0.30 s per GiB |
| Writer | one actor per partition, group commit, three ack policies (`NONE`, `WRITTEN`, `FORCED`) |
| Transport | own selector loop, or blocking sockets on virtual threads — both serve the same session code |
| FETCH | `transferTo` from the segment to the socket, or a heap copy; the slice is held across header **and** body so retention cannot move the log underneath a response |
| Wire | big-endian, `[int32 length][int16 apiKey][int16 apiVersion][int32 correlationId]`, versions per request rather than global |

| Module | |
|---|---|
| `booblik-core` | storage: segment, sparse index, two write paths, `transferTo` |
| `booblik-client` | the client **and the shared codec** — what a consumer of the library pulls in |
| `booblik-net` | the server: selector, sessions, two transports, two FETCH paths |
| `booblik-app` | running it: configuration, metrics, retention, distribution, health check |
| `booblik-benchmark` | the numbers: JMH through kotlinx-benchmark, plus the probes |

The codec lives with the client rather than with the server because it is **shared**: both sides
have to read the wire the same way, and a module both of them see is the only place a compiler
checks that.

## Testing

104 tests across 23 classes, and the gate is one command:

```bash
./gradlew check
```

Some of them are worth naming, because they are what the ordinary ones do not cover:

- **Crash tests** kill a child JVM with `SIGKILL` mid-write, at varying moments, and demand that
  every record the log admits to having is readable and matches its checksum — and that the next
  append continues from the boundary recovery chose
- **Property tests** over random sequences of appends, rolls and truncations, with segments small
  enough that boundaries actually get hit
- **A no-locks gate** that reads the bytecode of the hot path and fails on `MONITORENTER`
- **A stress test** for the session loop, whose comment carries a warning: do not raise the round
  count past the ephemeral port range, or the harness runs out of ports and blames the broker
- **`ci/smoke.sh`** starts the built distribution and speaks the protocol to it from Python that
  shares no code with the implementation — which is how it caught the record header growing from
  four bytes to eight
- **`ci/docker-smoke.sh`** checks the image in a running container: the user, the six profile flags
  in the live process, configuration from the environment, the health check telling a live port from
  a dead one, and that a pre-sized segment is still sparse on disk

A milestone does not close without a measurement, and a measurement does not count if the stand is
not named. `MeasurementDir` refuses to run on tmpfs at all: `/tmp` is a RAM disk on most Linux
distributions, and a durability probe there reports `fsync` at 0.01 ms — a number that is not slow
or fast but simply about something else.

## What booblik is not

- **Not a cluster.** One process, no replication, no leader election, no consumer groups. A broker
  that dies takes its partitions with it until it comes back.
- **Not a Kafka client or server.** The wire protocol is its own, deliberately smaller, and it will
  not talk to anything in the Kafka ecosystem.
- **Not configurable at runtime.** Topics and partition counts are fixed at startup; there is no
  create-topic request and no plan for one.
- **Not encrypted or compressed.** Both would mean touching the bytes on the read path, which is
  exactly what the zero-copy path exists not to do.
- **Not multiplatform.** The client is Kotlin/JVM. What is not portable in it is enumerable —
  sockets, `ByteBuffer`, two primitives from `java.util.concurrent` and `CRC32C` — and it now sits
  in one module, so the option is kept without paying for it today.

## Documentation

[docs/](docs/README.md) — layered documentation: research, features with their BDD scenarios, the
wire protocol, and a document per module. Written in Russian; code, KDoc and comments are in
English.

Read [research-architecture](docs/research/research-architecture.md) before changing anything. It
separates what was verified against a source from what was assumed, and it records the premises of
the original design draft that did not survive contact with the sources.
[BACKLOG.md](BACKLOG.md) holds the work items and, per milestone, what came out differently than
planned.

## License

Apache 2.0. See [LICENSE](LICENSE).
