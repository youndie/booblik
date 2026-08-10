# booblik

A message broker in Kotlin/JVM built on an append-only log: a topic splits into partitions, a
partition into segments, and a consumer reads by a numeric offset it keeps itself.

The niche is not "a Kafka replacement". It is a broker that fits in one head and one process —
no ZooKeeper, no KRaft quorum, no group coordinator. Everything that makes Kafka operable as a
cluster is deliberately absent; everything that makes a log fast is reproduced and **measured**.

## Status

Milestones M0 through M5 are done: the storage layer end to end (segment, sparse offset index, two
write paths, partition rolling, recovery, retention, a batching writer actor) and the network layer
(own selector-based acceptor, binary protocol, FETCH straight from the page cache to the socket),
a client library (pipelined connection, an accumulating producer, a consumer that keeps its own
offset), and a broker you can actually run: configuration validated at startup, metrics, retention,
and a distribution.

The broker answers over TCP and sustains **267 thousand records per second** — which is exactly the
ceiling of its own log, so the network layer costs nothing measurable.

```bash
./gradlew :booblik-app:installDist && ./ci/smoke.sh
```

Topic creation is not coming — the set of partitions is fixed at startup on purpose. Neither are
TLS and compression: they are incompatible with zero-copy by construction.

## Runtime footprint

booblik targets a deliberately small JVM, and that is a constraint rather than a tuning knob —
tests and benchmarks both run under it, so an allocation the hot path is not supposed to make
fails the gate instead of production:

```
-XX:+UseSerialGC -XX:ReservedCodeCacheSize=32M -XX:MaxDirectMemorySize=32M
-Xss256k -XX:MaxMetaspaceSize=80M -Xmx64M
```

Measured cost: none worth mentioning — seven of eight benchmark rows match a default JVM within
error. Note that `-Xmx` does **not** bound a mapped segment: `FileChannel.map` is neither heap
nor direct-buffer memory.

## Build

Requires a JDK 25 toolchain.

```bash
./gradlew build
```

The gate is one command — tests and ktlint 1.8.0 across every module:

```bash
./gradlew check
```

A milestone does not close without a measurement:

```bash
./gradlew :booblik-benchmark:mainBenchmark
```

## Documentation

Documentation is in Russian; code, KDoc and comments are in English.

Start at [docs/](docs/README.md) — the layered documentation index — or go straight to
[docs/research/research-architecture.md](docs/research/research-architecture.md), which is the
entry point for anyone picking up a task: it records what was verified against sources, what was
decided and why, and which premises of the original design draft did not survive contact with
the sources.

Numbers live in [docs/benchmarking.md](docs/benchmarking.md). Work items live in
[BACKLOG.md](BACKLOG.md).
