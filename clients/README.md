# Clients

Client libraries for booblik, one directory per language, each written from scratch against
[the protocol](../docs/api/protocol-wire.md) and held to [the conformance kit](../conformance).

All six publish and all six read, and each answers the same fourteen conformance checks. That took
two milestones rather than one: publishing was M-131 to M-135, reading was M-136 (Go) and M-138 (the
rest), because the reading half is where the expensive parts are — the checksum, the truncated tail,
a position the reader has to keep, and a long fetch.

| | coordinates | roles | dependencies |
|---|---|---|---|
| [`go/`](go/README.md) | `github.com/youndie/booblik/clients/go` | producer, **consumer** | none |
| [`python/`](python/README.md) | `booblik` on PyPI | producer, **consumer** | none |
| [`python-asyncio/`](python-asyncio/README.md) | same package, `booblik.aio` | producer, **consumer** | none — **another API, not another package** |
| [`node/`](node/README.md) | `booblik` on npm | producer, **consumer** | none |
| [`dotnet/`](dotnet/README.md) | `Booblik` on NuGet | producer, **consumer** | none in the library; xunit for its tests |
| [`java/`](java/README.md) | `booblik-java` on reposilite | producer, **consumer** | none in the library; JUnit for its tests |
| [`kotlin-native/`](kotlin-native/README.md) | `booblik-native` on reposilite | producer, **consumer** | none — **a target, not a reimplementation** |

Five reimplementations plus one target. Kotlin/Native is the odd one out on purpose: its sources
live in `booblik-native/` in the main Gradle build and it **shares** the codec, the ids and the
partitioner with the JVM client through `booblik-protocol/`, which compiles for jvm, linuxX64 and
macosArm64 from one source. The other four share nothing but the specification.

No shared code between the five, and **no dependencies in any of the libraries**: the whole client
is a socket and integer arithmetic in every language. Only .NET's and Java's tests need packages,
neither runtime having a test runner in the box.

**Java is a reimplementation and not a facade over the Kotlin client, and that was decided the hard
way** — the facade was written first and thrown away. It fixed the syntax, which is a real problem:
Kotlin mangles the names of functions with `value class` parameters, so `topic-7zVQyJo` and
`produce-iPAU26k` are not awkward from Java but *unspellable*, a hyphen being illegal in an
identifier. What it could not fix is the reason a service picks Java in the first place — it would
still have put kotlin-stdlib (1.8 MB) and kotlinx-coroutines (1.5 MB) on that service's classpath.

Each language's own trap is different, which is the argument for the golden vectors in one sentence:
Java and Kotlin need a mask because their bytes are signed, Python needs one because its integers
never overflow, JavaScript needs `Math.imul` because its numbers are doubles, and C# needs
`unchecked` said out loud. Go alone needs nothing — and is checked anyway.

**The checksum splits them the same way, and Go read first because of it.** CRC-32C is in Go's
standard library with the hardware instruction behind it; Python, Node and .NET each carry forty
lines of table instead, because in all three the *wrong* function is the one available under the
right name — `zlib.crc32` and `System.IO.Hashing.Crc32` are both "CRC32" and neither is this one, and
a package would make verification optional to install. Java is the other exception:
`java.util.zip.CRC32C` has been in the JDK since 9, well under this client's target of 17, and sits
one line away from `java.util.zip.CRC32`.

The traps at that line are not the same either, and the mutations say so rather than the intuitions:
Python needs the stored sum read unsigned (7 tests catch it), JavaScript needs `>>> 0` because
bitwise operators produce signed 32-bit results (10 tests), Java needs the cast from
`CRC32C.getValue()`'s `long`, and Kotlin/Native needs the reflected polynomial written so that it
cannot be mis-derived — the first version of that line was `0x829BB538` and produced perfectly
stable, perfectly wrong sums. **C# needs nothing**: a cast between `int` and `uint` preserves the
bits, so the mistake other languages punish is not one there. That was written down as a trap first
and corrected by measurement.

## Why the Kotlin client is not here

[`booblik-client`](../booblik-client) stays in the main Gradle build, and that is structural rather
than historical: it is not only a client. The **server's half of the codec lives in it**, and the
broker — `booblik-net` — depends on that, so both sides of the protocol are read by modules the
compiler checks together. Filing it under "one of N interchangeable implementations" would describe
something that is not true.

Two directories here hold only their two scripts, their sources living elsewhere, and both are
deliberate: **Kotlin/Native** is a multiplatform target of the shared `booblik-protocol` with its
sources in `booblik-native/`, and **python-asyncio** is a second API over the same package as
`python/`, sharing its codec through `booblik/wire.py`. Both still get their own conformance run,
because both are a different way of moving the bytes.

## What a client directory must contain

Two executables, and the top-level gate and CI know nothing else about any language:

| | |
|---|---|
| `gate.sh` | the client's own checks — lint, unit tests, whatever that ecosystem calls them |
| `conformance-client.sh` | builds if it must, then `exec`s the client under test |

`conformance-client.sh` implements the [client contract](../conformance/README.md#the-client-contract):
argv verbs, `key=value` on stdout, broker in `BOOBLIK_BROKER`.

**Exit 77 means "skipped".** A `gate.sh` on a machine without its toolchain should exit 77 rather
than 0: `ci/gate.sh` then names it as skipped instead of counting it green. It is the automake
convention and nothing else in this repository uses that code.

Run everything this machine can:

    ./ci/gate.sh

Run one client, and then the kit against it:

    ./ci/gate.sh go
    ./conformance/run.sh clients/go/conformance-client.sh

## Versions

**Each client versions independently**, and what ties them together is not a number — it is that
they pass the same conformance kit and speak the same protocol version. A fix in Go should not
force a JVM release, and a JVM release should not push four unchanged packages into four registries.

Every client's README states the protocol version it speaks (currently PRODUCE and METADATA v1,
FETCH v2) and the roles it declares to the kit.

## Publishing

One tag shape for all of them — `clients/<language>/vX.Y.Z` — and it is Go's shape rather than a
convention chosen here: a Go module in a subdirectory is *required* to be tagged that way, so the
rest follow instead of one language having its own.

**No long-lived credential publishes any of them.** PyPI, npm and NuGet all exchange a GitHub OIDC
token for a key that lives minutes to an hour; Go has no registry to authenticate against; the Maven
repository's credentials were already there. NuGet was written here with an API key first and
corrected — its trusted publishing is the same mechanism as the other two.

| | goes to | what it costs |
|---|---|---|
| `go/` | nowhere — the tag **is** the release | nothing |
| `python/` | PyPI | a trusted publisher, no secret |
| `node/` | npm | a trusted publisher, no secret |
| `dotnet/` | NuGet.org | a trusted publisher, no secret |
| `java/` | the Maven repository | the credentials that were already there |
| `kotlin-native/` | the Maven repository, from `publish.yml` | a macOS runner, see below |

**GitHub Packages was considered and does not fit**, which is worth writing down because it looks
like it should. Python is not among its registries at all, and for npm, Maven and NuGet its own
documentation says a package must be authenticated for "regardless of whether the package is public
or private" — only the container registry allows anonymous pulls. Checked without a token:
`maven.pkg.github.com` answers 401, `ghcr.io` answers 200, and the reposilite this repository
already uses answers 200. Moving there would have handed every consumer a personal access token.

Installing from git is not a substitute either: **npm has no subdirectory syntax** — the fragment
after `#` takes a commit-ish or a semver range and nothing else — and the package lives in
`clients/node/`. pip (`#subdirectory=`) and Go (the subdirectory *is* the module path) can do it,
but what they install is a reference rather than a version.

**Kotlin/Native and the shared protocol publish from macOS**, because the `macosArm64` klib builds
nowhere else while `linuxX64` builds there too. A multiplatform module has to publish its root and
every target in one run from one host, or the root points at variants that do not exist.

Each workflow ends by installing what it just published — from the registry, into a container that
has never seen this checkout — and driving a real broker with it. That step is not ceremony: the
JVM client's 0.1.1 was published by a green run and did not compile at the consumer, and nothing
short of consuming it would have said so.

## CI

One workflow file per language, because GitHub Actions applies `paths:` to a whole workflow rather
than to a job. Each must list `conformance/**` among its paths as well as its own directory: the kit
is shared, and a change to it has to re-run every client, not just the one whose files moved.

## The one thing Go pays for the monorepo

A Go module is identified by its path in the repository, so this one is imported as
`github.com/youndie/booblik/clients/go` and its versions are tagged **`clients/go/v0.1.0`** rather
than `v0.1.0`. That is Go's convention for a module in a subdirectory, not a workaround.

The package inside declares `package booblik`, not `package go` — `go` is a keyword, and a directory
name that differs from the package name is ordinary in Go (`gopkg.in/yaml.v3` is `yaml`). Some
linters mention it; nothing breaks.
