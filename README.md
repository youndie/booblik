# booblik

A message broker in Kotlin/JVM built on an append-only log: a topic splits into partitions, a
partition into segments, and a consumer reads by a numeric offset it keeps itself.

The niche is not "a Kafka replacement". It is a broker that fits in one head and one process —
no ZooKeeper, no KRaft quorum, no group coordinator. Everything that makes Kafka operable as a
cluster is deliberately absent; everything that makes a log fast is reproduced and **measured**.

## Status

Milestone M0 is done: the storage layer (segment, sparse offset index, two write paths, handing
bytes to a channel), a gate made of tests and ktlint, a benchmark module, and the first
measurement. **There is no network yet** — `:booblik-net` does not exist, on purpose.

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
