import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import { Connection } from "../src/connection.js";
import { crc32c } from "../src/crc32c.js";
import { Code, CorruptRecordError, RecordExceedsMaxBytesError } from "../src/errors.js";
import { FakeBroker } from "./fake-broker.js";

// A broker and a connection per test, for the same reasons connection.test.js gives: a file-level
// fixture holds the event loop open when every test is filtered out, and makes offsets depend on
// the order tests happen to run in.
async function fixture(t, options = {}) {
  const broker = await FakeBroker.start(1);
  const connection = await Connection.connect(broker.address);

  t.after(async () => {
    connection.close();
    await broker.close();
  });
  // The fixture answers whatever it is asked immediately, so waiting would only be time spent.
  const consumer = connection.consumer("t", 0, 0, { maxWaitMillis: 0, ...options });
  return { broker, connection, consumer };
}

// Walks up rather than trusting the working directory: `node --test` runs from this client's
// directory and an editor may not, and a fixture that resolves in one but not the other gets
// deleted by whoever hits it second.
function readVectors(name) {
  let directory = dirname(fileURLToPath(import.meta.url));
  for (;;) {
    const candidate = join(directory, "conformance", "vectors", name);
    try {
      return readFileSync(candidate, "utf8")
        .split("\n")
        .filter((line) => line && !line.startsWith("#"))
        // split keeps empty fields, so the empty-payload vector arrives as a leading "" — which is
        // the vector a hand-written parser drops first.
        .map((line) => line.split("\t"));
    } catch {
      const parent = dirname(directory);
      assert.notEqual(parent, directory, `conformance/vectors/${name} not found above the test`);
      directory = parent;
    }
  }
}

// Holds this implementation to vectors computed by another one, in another language. "CRC32" names
// at least three different functions, all of which return a plausible number, so agreeing with an
// independent reading of the specification is the only property worth asserting.
//
// If this fails, this code is wrong — not the vectors.
test("the checksum matches the golden vectors", () => {
  const rows = readVectors("crc32c.tsv");
  assert.ok(rows.length > 0, "no vectors loaded");

  for (const [payloadHex, expected, name] of rows) {
    assert.equal(crc32c(Buffer.from(payloadHex, "hex")), Number(expected), `vector ${name}`);
  }
});

test("the checksum is Castagnoli and not zlib's CRC-32", () => {
  // zlib.crc32, which Node also has, gives 0xCBF43926 for this input.
  assert.equal(crc32c(Buffer.from("123456789")), 0xe3069283);
});

test("the checksum is unsigned", () => {
  // Bitwise operators produce signed 32-bit results in JavaScript, so without the final `>>> 0`
  // about half of all sums come out negative — and a negative sum matches nothing the broker wrote.
  for (const payload of [Buffer.alloc(0), Buffer.from([0x80]), Buffer.from("booblik")]) {
    assert.ok(crc32c(payload) >= 0, `${payload.toString("hex")} hashed to a negative number`);
  }
});

test("records come back in order, and the position moves past them", async (t) => {
  const { broker, consumer } = await fixture(t);
  const payloads = [
    Buffer.from(Array.from({ length: 256 }, (_, index) => index)),
    Buffer.from([0x00]),
    Buffer.from("third"),
  ];
  broker.seed("t", 0, ...payloads);

  assert.deepEqual(await consumer.poll(), payloads);
  assert.equal(consumer.position, 3);
  assert.equal(consumer.highWatermark, 3);
  assert.equal(consumer.lag, 0);
});

// The steady state of a consumer that is keeping up, and the one it must not read as the end of the
// log.
test("fetching at the high watermark is empty and not an error", async (t) => {
  const { broker, consumer } = await fixture(t);
  broker.seed("t", 0, Buffer.from("one"));

  await consumer.poll();
  assert.deepEqual(await consumer.poll(), []);
  assert.equal(consumer.position, 1);
});

// maxBytes cuts on a byte boundary, so a full response normally ends inside a record. Returning the
// fragment corrupts data; counting it as the end of the log stalls for ever.
test("a truncated tail is dropped and re-fetched whole", async (t) => {
  // One whole record is 8 bytes of header and 100 of payload; 150 stops inside the second.
  const { broker, consumer } = await fixture(t, { maxBytes: 150 });
  const first = Buffer.alloc(100, "A");
  const second = Buffer.alloc(100, "B");
  broker.seed("t", 0, first, second);

  assert.deepEqual(await consumer.poll(), [first]);
  assert.equal(consumer.position, 1, "the partial record must not be counted");
  assert.deepEqual(await consumer.poll(), [second]);
});

// The other branch: no size field to read, so the truncation is found by having bytes left over.
test("a response stopping inside a record header is truncation", async (t) => {
  // 28 bytes is the first record whole, then 4 bytes into the second record's 8-byte header.
  const { broker, consumer } = await fixture(t, { maxBytes: 32 });
  broker.seed("t", 0, Buffer.alloc(20, "A"), Buffer.alloc(20, "B"));

  assert.equal((await consumer.poll()).length, 1);
  assert.equal(consumer.position, 1);
});

// The stall that does not resolve itself: every retry makes the identical request, so the consumer
// keeps running, reports nothing and never advances.
test("a record larger than maxBytes is reported rather than retried", async (t) => {
  const { broker, consumer } = await fixture(t, { maxBytes: 100 });
  broker.seed("t", 0, Buffer.alloc(500, "A"));

  await assert.rejects(() => consumer.poll(), (error) => {
    assert.ok(error instanceof RecordExceedsMaxBytesError);
    assert.equal(error.recordBytes, 500);
    assert.equal(error.maxBytes, 100);
    assert.equal(error.offset, 0);
    return true;
  });
  assert.equal(consumer.position, 0, "the position must not move past an unread record");
});

// The client is the only party that can catch this: on the zero-copy path the broker never touches
// the record bytes it sends.
test("a corrupt record is rejected", async (t) => {
  const { broker, consumer } = await fixture(t);
  broker.seed("t", 0, Buffer.from("payload"));
  broker.corrupt = true;

  await assert.rejects(() => consumer.poll(), (error) => {
    assert.ok(error instanceof CorruptRecordError);
    assert.equal(error.offset, 0);
    assert.notEqual(error.stored, error.computed);
    return true;
  });
});

// One line apart in the decoder: the length check has to come before the checksum, or every full
// response is an alarm.
test("truncation is not reported as corruption", async (t) => {
  const { broker, consumer } = await fixture(t, { maxBytes: 150 });
  broker.seed("t", 0, Buffer.alloc(100, "A"), Buffer.alloc(100, "B"));

  assert.equal((await consumer.poll()).length, 1);
});

// Always v2, so the waiting fields are not exercised only in the branch nobody debugs. Asserted from
// the broker's side, the only place that can tell what was actually sent.
test("FETCH goes out as v2 with the waiting fields", async (t) => {
  const { broker, connection } = await fixture(t);
  broker.seed("t", 0, Buffer.from("one"));

  await connection.fetch("t", 0, 0, { maxBytes: 4096, maxWaitMillis: 250, minBytes: 64 });

  assert.equal(broker.lastVersion, 2);
  assert.deepEqual(broker.lastFetch, {
    topic: "t",
    partition: 0,
    offset: 0,
    maxBytes: 4096,
    maxWaitMillis: 250,
    minBytes: 64,
  });
});

test("a refusal reaches the caller", async (t) => {
  const { broker, consumer } = await fixture(t);
  broker.refuseWith = Code.OFFSET_OUT_OF_RANGE;

  await assert.rejects(() => consumer.poll(), { code: Code.OFFSET_OUT_OF_RANGE });
});

test("seek moves the position", async (t) => {
  const { broker, consumer } = await fixture(t);
  broker.seed("t", 0, Buffer.from("zero"), Buffer.from("one"), Buffer.from("two"));

  consumer.seek(2);
  assert.deepEqual(await consumer.poll(), [Buffer.from("two")]);
});

test("for await yields every record once", async (t) => {
  const { broker, consumer } = await fixture(t);
  const seeded = [Buffer.from("a"), Buffer.from("b"), Buffer.from("c")];
  broker.seed("t", 0, ...seeded);

  const read = [];
  for await (const record of consumer) {
    read.push(record);
    if (read.length === seeded.length) break;
  }
  assert.deepEqual(read, seeded);
});

// `break` has to end the fetching too, not just the loop — an iterator that kept a fetch in flight
// would hold the process open after the caller has finished with it.
test("breaking out of the loop stops the fetching", async (t) => {
  const { broker, consumer } = await fixture(t);
  broker.seed("t", 0, Buffer.from("a"), Buffer.from("b"));

  for await (const _ of consumer) break;

  const before = broker.lastFetch;
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.deepEqual(broker.lastFetch, before, "a fetch went out after the loop ended");
});
