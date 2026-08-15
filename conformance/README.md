# Conformance

What every booblik client is held to, whatever language it is written in.

    ./conformance/run.sh                      # the reference client
    ./conformance/run.sh '<your client>'      # yours

Two halves. The **vectors** pin the algorithms that never appear on the wire and must still agree
between any two clients. The **harness** drives a client through a live broker and checks what
actually happened, from the outside.

## Why any of this exists

- **the partitioner**, because the key never reaches the broker — the client picks the partition and
  sends the number. Two publishers that hash a key differently put it in two partitions, and per-key
  order, the thing partitions exist to provide, is gone. Nothing errors: the record lands in a
  partition that exists, just not the same one;
- **the checksum**, because on the zero-copy read path the broker never touches the record bytes. A
  client is the only thing that can verify them, so one that skips it silently switches off the
  project's only defence against a corrupted log.

Both are specified in bytes in [`docs/api/protocol-wire.md`](../docs/api/protocol-wire.md) §7.

Every producer check is verified by the harness's own reader, never by the client under test.
Reading a Go producer's records back with that same Go producer proves it agrees with itself, which
is exactly what a wrong partitioner also does.

## The client contract

A command-line one, and deliberately the smallest thing that works in every language. An HTTP
control API would mean writing a server before writing a producer; a library to link against would
not be language-neutral at all. Reading argv and printing lines is about fifty lines in anything,
including C.

The client is invoked as `<command> <verb> [args…]` with the broker in the environment:

    BOOBLIK_BROKER=host:port

It prints `key=value` lines to stdout; repeated keys are lists. stderr is diagnostics, shown only
when a check fails.

**Exit 0 means the verb was carried out** — including when the broker refused the request, which is
a result and is reported as `error=UNKNOWN_TOPIC_OR_PARTITION`. A non-zero exit means the client
itself failed, and that is never an expected outcome.

| verb | arguments | answers |
|---|---|---|
| `capabilities` | — | `roles=producer[,consumer]`, `name=…` |
| `metadata` | `<topic>` | `partition=<id> <logStartOffset> <highWatermark>`, repeated |
| `produce` | `<topic> <partition> <ack> <hex>[,<hex>…]` | `baseOffset=`, `logEndOffset=` |
| `produce-keyed` | `<topic> <keyHex> <payloadHex>` | `partition=`, `baseOffset=` |
| `fetch` | `<topic> <partition> <offset> <maxBytes>` | `highWatermark=`, `record=<hex>` repeated, `recordExceedsMaxBytes=<n>` |

`ack` is `none`, `written` or `forced`.

**A client declares its roles and is only asked for what it declared.** A producer-only client
skips the consumer checks and still reports a pass — the roles exist so that a publisher-only
library is a first-class thing rather than a client failing four checks for ever. A run where
*everything* was skipped is a failure, not a pass.

The reference implementation is
[`booblik-conformance`](../booblik-conformance/src/main/kotlin/ru/workinprogress/booblik/conformance/Main.kt);
read it for what a real answer looks like.

## Traps the checks are aimed at

Each check is here because its failure is **silent**. Nothing here checks that a correct client is
correct.

- **`ack=none` answers nothing at all** — not an empty response, nothing, because no offset exists
  until the writer reaches the batch. A client that reads a response after that request blocks for
  ever, or worse reads the *next* request's response and hands it to the wrong caller. This is the
  most common way to write a first producer in a new language;
- **signed bytes.** `0x80` must enter the hash as 128, not −128. Languages whose bytes are signed
  (Java, Kotlin, C#'s `sbyte`, Rust's `i8`) need an explicit mask, and a client that gets it wrong
  passes every ASCII key before failing on the first name not written in Latin;
- **the wrong CRC.** CRC-32C is Castagnoli, polynomial `0x1EDC6F41`. It is *not* `zlib.crc32`, not
  `System.IO.Hashing.Crc32`, and not `java.util.zip.CRC32`. All are called "CRC32" and all return a
  plausible number. The check value for `123456789` is `0xE3069283`. Go has it in the standard
  library (`hash/crc32.Castagnoli`); Python, Node and .NET need a package or about forty lines;
- **the truncated tail.** `maxBytes` bounds a FETCH response in bytes, not in records, so it can
  stop mid-record. A client that returns the fragment corrupts data; one that reads it as the end
  of the log stalls for ever without erroring;
- **the record that never fits.** The same truncated tail with *nothing whole before it* means the
  next record is larger than `maxBytes`, and no number of retries will change that. Dropping the
  tail and asking again — which is right in every other case — produces the identical request for
  ever: running, silent, not advancing, and from the outside indistinguishable from a consumer that
  has caught up. The client is the only party that can tell the two apart, so it has to say so:
  `recordExceedsMaxBytes=<size of the record>` and no `record=` lines;
- **zero-length records are refused** with `CORRUPT_REQUEST`, and the refusal has to reach the
  caller. Recovery reads a length prefix first and cannot tell a zero length from unwritten space,
  so an empty record would end the log at itself on the next restart. The harness found this on its
  first run against a protocol document that did not mention it.

## Running against your own broker

    BOOBLIK_BROKER=host:port ./conformance/run.sh '<your client>'

The broker must have been started with `BOOBLIK_TOPICS=conformance:3,single:1`. Starting one is
`run.sh`'s job only when you have not pointed it at one yourself — the two are separate so the
harness can be aimed at a staging broker or at one inside a compose network.

**Three partitions and not four, and that is measured rather than tidy.** A power-of-two count folds
on the low bits of the hash, and the low bit of FNV-1a and the low bit of `Arrays.hashCode` are the
same function — both come out as the parity of the low bits of the key's bytes, because `0x01000193`
and `31` are odd. Over 200 000 random keys the two picked the same partition 52.94% of the time at
four partitions against the 25% chance would give, and 33.40% at three against 33.33%. Swapping the
partitioner for the old one is caught by 8 vectors of 11 at three partitions and by **1** at four.
(The same measurement says the distribution is fine either way, under 2% off even at sixteen — this
is about what the check can see, not about the partitioner.)

## Regenerating the vectors

    python3 conformance/vectors/generate.py

TSV is canonical and JSON is the mirror: Kotlin/JVM and C have no JSON parser to hand, and
tab-separated fields are three lines of parsing everywhere. Lines starting with `#` are comments and
the last one is the header. Keys and payloads are hex, so the empty key is an empty leading field —
deliberately, because it is the vector a hand-written parser drops first.

The generator is a **second** implementation, in a different language from the client it checks: a
dump of what the Kotlin client produces would prove only that it agrees with itself. It asserts the
published CRC-32C check value before writing anything.

**If a check fails, the code is wrong, not the vectors.** Regenerating to go green turns a fixture
into an echo of whatever it was meant to catch. The one honest reason to regenerate is adding
vectors.
