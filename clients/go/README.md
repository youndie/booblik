# booblik for Go

A client for [booblik](../../README.md) — PRODUCE, FETCH and METADATA. The first of the six to read
as well as write (M-136), Go being the language where the checksum is in the standard library; the
other five followed in M-138 and all six answer the same fourteen checks.

    go get github.com/youndie/booblik/clients/go

The import path is the directory, because that is how Go identifies a module in a monorepo. The
package is `booblik`.

```go
import (
    "context"

    booblik "github.com/youndie/booblik/clients/go"
)

conn, err := booblik.Dial(ctx, "localhost:9092")
defer conn.Close()

topic, err := conn.Topic(ctx, "orders")           // partitions come from the broker
_, err = topic.Send(ctx, key, payload, booblik.AckWritten)
```

## Batch, or lose most of the broker

One record per request is the single most expensive mistake available here: the broker's own
measurements put batches of a hundred at **4 335 482 records/s against 80 592** one at a time. That
is a larger factor than every other decision in the project put together, so batching is not an
optimisation to enable later — it is what a producer is.

Either batch by hand, when records are already together:

```go
result, err := conn.Produce(ctx, "orders", partition, records, booblik.AckWritten)
// records land contiguously from result.BaseOffset
```

or let a `Producer` do it, when they arrive one at a time:

```go
producer := booblik.NewProducer(conn, booblik.DefaultProducerConfig())
defer producer.Close()                              // Close flushes what is queued

promise, err := producer.Send(ctx, "orders", partition, payload)
offset, err := promise.Await(ctx)
```

A `Producer` **owns its `Conn`**: one goroutine holds the pending records and is the only writer to
that socket. Do not use the same `Conn` directly while a `Producer` has it — responses are matched
in order, and a second writer takes somebody else's answer.

## Reading

```go
consumer := conn.Consumer("orders", 0, offset)      // the offset is yours to keep

for record, err := range consumer.Records(ctx) {
    if err != nil {
        return err
    }
    handle(record)
}
persist(consumer.Position())                        // where to resume
```

`Records` is a range-over-func iterator, not a channel — it runs on the caller's goroutine, at the
caller's pace, and can return the error that ended it. A channel could do none of those: stopping
early leaks the goroutine feeding it, a buffer reads ahead of what has been handled, and there is
nowhere for an error to go. `Poll` is the same thing one fetch at a time, when the loop belongs to
somebody else.

**The loop does not end.** A partition has no end, only a place it has not been written to yet;
cancel the context to stop, which ends the iteration without reporting an error.

**The position lives in this client.** There are no consumer groups, no coordinator and no committed
offsets in this broker — an offset is a number the reader already knows, and asking a broker to
remember it is what drags in cluster consensus. `Position()` is the number to write down, and
writing it down *after* the records are dealt with rather than before is what makes a restart
re-deliver instead of skip.

Reading from the beginning means starting at the partition's `LogStartOffset` from `Metadata`, not
at zero: zero is `OFFSET_OUT_OF_RANGE` on any topic that has ever dropped a segment to retention.

## Four things a reader gets wrong

- **the wrong CRC32.** The sum is CRC-32C (Castagnoli). `crc32.ChecksumIEEE` is zlib's, a different
  polynomial with the same name, and a client using it rejects every record it reads. Go has the
  right one in `hash/crc32`, which is why this is the first client that reads at all;
- **an empty response is not the end of the log.** It is what a consumer that has caught up gets,
  which is the steady state of every consumer keeping up. `MaxWait` (5s by default) is what keeps
  that from becoming a busy loop: the broker holds the request and answers the moment a record
  lands;
- **a response can stop inside a record**, because `MaxBytes` cuts on a byte boundary. The fragment
  is dropped and `Position` stops before it, so the next fetch asks for that record from its start.
  Returning the fragment corrupts data; counting it as the end of the log stalls for ever;
- **a record bigger than `MaxBytes` never arrives whole**, so retrying is the stall. That one is an
  error — `ErrRecordExceedsMaxBytes`, carrying both numbers, because raising `MaxBytes` is the only
  fix.

Records point into the response buffer without copying, so keeping one record out of a megabyte
response keeps the megabyte. Copy what you retain.

## Three things that are not obvious

- **`AckNone` answers nothing at all** — not an empty response, nothing, because no offset exists
  until the writer reaches the batch. `Produce` returns `(nil, nil)`, and a `Promise` completes with
  `OffsetUnknown`. It is also the only mode where the broker may drop an accepted record without
  telling anyone;
- **the key never reaches the broker.** The record format has no room for one, so this client picks
  the partition and sends the number. That is why the partitioner is pinned by
  [golden vectors](../../conformance) computed in another language: two publishers that disagree put
  one key in two partitions, and nothing errors when they do;
- **a refusal keeps the connection.** `errors.Is(err, booblik.ErrUnknownTopicOrPartition)` is a
  result, not an outage — framing was intact, so the broker understood the request and declined it.

`PartitionFor(nil)` advances a round-robin counter, so asking and then sending is two turns of it.
With a key there is no such thing: the answer is a pure function of the key.

## Protocol version and roles

PRODUCE and METADATA at v1, FETCH at v2 — always v2, including when nothing is being waited for, so
that the waiting fields are not exercised only in the branch nobody debugs. Declares
`producer,consumer`, so all fourteen checks run against it.

## Checks

    ./clients/go/gate.sh                                          # gofmt, vet, go test -race
    ./conformance/run.sh clients/go/conformance-client.sh         # against a real broker

`go test` needs no Docker and no network: the tests run against a fake broker that **decodes what
this client encoded**, so an encoding mistake fails there rather than becoming a mystery later. The
conformance run is what catches a client that is wrong self-consistently, which unit tests by
construction cannot.

Both are in `./ci/gate.sh go`.

There is a third thing, and it is not a check but a measurement — the conformance kit fetches with
no wait at all, which leaves the client's own default unexercised:

    BOOBLIK_BROKER=localhost:9092 go run ./cmd/probe

It reports what a caught-up consumer costs with and without the long fetch, and how long after a
record is written a waiting consumer has it. Numbers and what they mean are in
[benchmarking](../../docs/benchmarking.md), measurement 24.
