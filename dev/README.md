# dev — booblik in services

Services in `docker compose`: a publisher writes events, consumers take them apart. The broker comes
**from GHCR** and the client **from reposilite**, not from the directory next door — the sample is
built exactly the way a stranger would build it.

```bash
./run.sh          # distributions, images, compose, wait until they answer
./check.sh        # assert what the sample claims, from outside, over HTTP
docker compose down -v
```

Four layers, one profile each: the base one is partition-per-consumer with the position kept by the
consumer, `--profile queue` is a task queue, `--profile projection` a read model, `--profile relay`
a bridge into Kafka and back.

## Layer 1: events spread across partitions, the consumer keeps its position

**The key picks the partition, and the client does it.** Every event belongs to a user, and the key
is that user. `TopicHandle` hashes it and sends the broker a **partition number**; the key itself
never reaches the wire. The consequence is the reason for doing it: one user's events all sit in one
partition, so one service handles them, in order.

**Each consumer reads its own partition.** The assignment is static, from the environment. That is
also — minus the automatic reassignment — what a Kafka consumer group gives you: a partition in a
group is read by exactly one consumer. What is missing here is taking over the partition of a
**failed** service; the second layer does that, as a protocol on top of the log rather than a
feature of the broker ([research-usecases](../docs/research/research-usecases.md)).

**The position belongs to the consumer and outlives the process.** `FileOffsetStore` is the first
real implementation of `OffsetStore` in the project: a file on a volume, written through a temporary
file and an atomic move. Half a saved position is worse than none — `"12"` truncated to `"1"` parses
without complaint and silently replays eleven records.

**The guarantee is at-least-once, and it is visible in the code.** `checkpointing` saves the offset
**after** the collector has handled the batch. A stop between handling and saving replays the batch;
saving first would skip it. That is why `check.sh` treats resuming slightly **behind** the last
position seen as legal, and fails only on a start from the beginning of the log.

### What the first layer's services answer

| | |
|---|---|
| `localhost:8080/stats` | publisher: how much was sent, the split across partitions, last offset |
| `localhost:8081/stats` | consumer-0, partition 0 |
| `localhost:8082/stats` | consumer-1, partition 1 |
| `localhost:8083/stats` | consumer-2, partition 2 |

The publisher's port is overridable with `PUBLISHER_PORT` — 8080 is taken more often than any other
port, and the sample should not be the thing that refuses to start because of it.

A consumer's `/stats` carries `resumedFrom` — where it started. `null` after a first run, a number
after a restart, and the most interesting field in the sample.

## Layer 2: a task queue with no coordinator

```bash
docker compose --profile queue up --build -d
./check-queue.sh
curl -s localhost:8091/stats    # worker-0, then 8092 and 8093
```

Three workers share one stream of tasks: each task is taken by exactly one, and if its worker dies
the task goes to somebody else. The broker contributes nothing to this — no per-record lock, no
acknowledgement, no redelivery — and that is a decision rather than a gap
([research-usecases](../docs/research/research-usecases.md), Р11).

**The arbiter is the order of the log.** Workers append claims to one partition of a `claims` topic,
and the claim that landed first wins. A partition is a total order and everyone reads the same one,
so there is nobody to ask and no need to ask.

**The verdict is a pure function of the log, and that is the decision here.** A lease expires by the
timestamp written **into the claim being examined**, never by the reader's clock. Two workers
replaying the same records therefore reach the same answer even when their clocks disagree: skew
changes who *tries* to take a task and when, not who *won*. Judging by a local `now()` looks
equivalent and is not — two workers reading a second apart would disagree about a lapsed lease, and
both would believe the task was theirs.

Taking a task costs a round trip: write the claim, then read the log until it comes back. A worker
recognises its own claim by the offset `send` returned — the same number the log uses to address the
record.

### What this queue does not give

* **at-least-once, not exactly-once.** A worker stalled past its lease wakes up and finishes a task
  somebody else has taken. There is no fencing: rejecting the late result would need a party that
  knows which lease is current, which is the coordinator this design exists to avoid.
* **The claims log grows** — two records per task — and lives on retention alone. Retention shorter
  than a lease is a silent bug: the claim disappears, the task looks free, and a second worker takes
  it while the first is still working.
* **Every worker reads every claim.** Traffic is workers × tasks. Fine for three, not for three
  hundred.
* **Leases are not renewed.** Work longer than the lease means a duplicate; here the lease is 30 s
  against 400 ms of work.

### What it costs (measurement 20)

```bash
./measure-queue.sh random 3 60      # strategy, workers, window in seconds
./check-redistribution.sh           # kill the holder of a task and watch it move
```

**The round trip through the log is cheap — p50 around half a millisecond.** What is expensive is
collisions, and one line removes them: take a **random** claimable task instead of the first. With
three workers and a backlog that is 1.00 attempts per task and no lost races, against 8–27 % for
`first`, at the same ceiling throughput. The compose default is `random` for that reason.

It only helps **with a backlog**. On an idle queue there is usually one claimable task, and choosing
at random from a list of one is `first`; the loss rate then describes the arrival rate against the
worker count rather than the protocol.

**Collisions come from workers being in step, not from there being many of them.** Thirty workers
with a backlog collide *less* than three — 1.6–3.7 % of attempts against 8–27 % — because 400 ms of
work finishes at different moments and they look at the head of the queue one at a time. Idle
workers, by contrast, all wake on the same arriving task. At thirty the wasted attempts cost nothing
in throughput either: all four runs sat at the arithmetic ceiling of 73.6 tasks/s
(measurement 21).

## Layer 3: a projection — state as a function of the log

```bash
docker compose --profile projection up -d --build
./check-projection.sh
curl -s localhost:8096/stats
curl -s "localhost:8096/top?n=3"
curl -s localhost:8096/user/user-3
```

The service stores **nothing**. Its state is a fold over the log, queries answer from it, and a
restart rebuilds everything by replaying. This is what a log is actually for, and it is the only
layer that uses both halves of the subscription for what they are:

* **`replay()` ends** — at the high watermark as it was when it started — which is the moment the
  view has caught up with history and can answer queries;
* **`follow()` does not**, so from then on the view stays current.

`RecordBatch.nextOffset` is what joins them without a gap: the replay reports where each partition
stopped and the tail starts there. Not `Latest`, which would skip whatever arrived while the view
was building, and not `Earliest`, which would count history twice.

**The position is deliberately not stored.** Persisting a position without the state it belongs to
is a silent corruption: the restart resumes at a late offset with an empty view, and from then on
the service answers confidently from data missing everything before that offset. Nothing crashes and
nothing says so. Persist both or neither; this one persists neither.

The check asserts exactly that: after a restart the replay must return **at least** what was there
before. A hand-made "3 events back against 228" is rejected.

**Order matters only inside a partition — and that is enough here because of what the publisher
does.** The key is the user, so one user's events share a partition and arrive in order. Take the key
away and the projection still counts correctly while `lastAction` starts reporting whichever event
happened to arrive last. This is the only layer that **consumes** what the first one demonstrates.

## Layer 4: a relay between Kafka and booblik

```bash
docker compose --profile relay up -d --build
./check-relay.sh                 # Kafka → booblik → Kafka, the whole round trip
curl -s localhost:8094/stats     # relay-in; 8095 is relay-out
```

booblik does not speak the Kafka protocol and is not going to: Kafka's `baseOffset` and CRC live
**inside the stored bytes**, so speaking Kafka means storing Kafka and giving up `transferTo` of our
own ([research-usecases](../docs/research/research-usecases.md), Р14). A relay puts booblik
**beside** the ecosystem: two libraries at the edges, both formats intact, the translation paid once
in user space.

**One module for both directions**, the direction in the environment. Configuration, HTTP, batching
and reconnection are shared; what differs is mostly **where the position lives**, and that is the
part worth seeing side by side:

| Direction | Who remembers the position |
|---|---|
| Kafka → booblik | Kafka, in a consumer group. Auto-commit is **off**: the order "write to booblik, then commit" is the at-least-once guarantee |
| booblik → Kafka | nobody but us: `FileOffsetStore` on a volume — the same one the first layer's consumer uses |

**A Kafka key does not survive the crossing.** On the way into booblik it still does its job — it
picks the partition, so per-key ordering is preserved — but it is not itself stored: booblik's wire
has no field for it, and it cannot be recovered on the way back. Wrapping it in an envelope would
force every other booblik consumer to parse an envelope it never asked for.

**Duplicates are legal.** Both sides move the position only after the far side acknowledged, so
`check-relay.sh` demands a complete set and **counts** repeats rather than failing on them.

### Traps that cost a run

The official Kafka image starts in KRaft mode with **no configuration at all** — and stays usable
exactly until a second container shows up. By default it advertises itself as `localhost:9092`, so a
client connects, is told in the metadata where the broker "really" is, and dials its own localhost.
The logs say `NoAvailableBrokersException` while `docker compose ps` shows everything healthy. The
cure is `KAFKA_ADVERTISED_LISTENERS`, and it is in the compose file.

The other half of that story was mine: the first version of `check-relay.sh` sent the producer's
output to `/dev/null`, leaving no way to tell a broken relay from records that never reached Kafka.
It now asks Kafka how many records it holds before waiting on anything.

## Traps common to the whole sample

**`repositories { }` in `subprojects` overrides the ones from `settings.gradle.kts`.** The default
`repositoriesMode` is `PREFER_PROJECT`, and project repositories do not add to the settings ones —
they **replace** them. It fails as `Could not find io.github.youndie.booblik:booblik-client`, which
reads like a missing artefact rather than a lost repository.

**Round-robin in `partitionFor(null)` advances a counter.** Asking which partition a record will go
to and then sending it is two turns of that counter, and the records start skipping partitions. With
a key there is no such thing: the keyed partitioner is a pure function of the key, whichever one is
configured. That is another reason the sample publishes with a key.

**The broker image is built for amd64.** The compose file names `platform` explicitly, because
otherwise an arm64 host reports `no matching manifest`, which reads like a broken tag.

## What this sample found in booblik itself

Two delivery defects, both invisible to a build that compiles the modules together.

**`0.1.1` — a library nothing could be compiled against.** Coroutines are in the public ABI and were
declared `implementation`, so the POM put them at runtime scope.

**`0.1.2` — the accumulator lost a record.** `Producer` waited for the next record with
`withTimeoutOrNull { mailbox.receive() }`, and cancelling a `receive` can take an element off the
channel and drop it: the caller waits for ever while everybody else is served as usual. The sample's
publisher stopped after a single task and carried on publishing events. This is **the same mistake
that had already been found and fixed on the server side** — the client carried it past every test
because none of them drove two topics at different rates through one accumulator. Fixed in `0.1.3`.
