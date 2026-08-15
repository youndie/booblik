import assert from "node:assert/strict";
import { test } from "node:test";

import { AckPolicy, Connection, decodeMetadata } from "../src/connection.js";
import { BrokerError, Code, ProtocolError } from "../src/errors.js";
import { partitionFor } from "../src/partition.js";
import { FakeBroker } from "./fake-broker.js";

const ALL_BYTES = Buffer.from(Array.from({ length: 256 }, (_, index) => index));

// A broker and a connection per test, registered for cleanup on the test itself.
//
// File-level `before`/`after` was the first shape of this and it was wrong twice over. It leaves an
// open socket holding the event loop when a run filters every test in the file out — the suite then
// hangs rather than finishing. And it makes the tests share one broker, so assertions about exact
// offsets quietly depend on the order they happen to run in.
async function fixture(t, partitions = 3) {
  const broker = await FakeBroker.start(partitions);
  const connection = await Connection.connect(broker.address);

  t.after(async () => {
    connection.close();
    await broker.close();
  });
  return { broker, connection };
}

test("a record arrives byte for byte, every byte value included", async (t) => {
  // An encoding that damages the payload usually damages the high half and leaves ASCII intact.
  const { broker, connection } = await fixture(t);

  const records = [ALL_BYTES, Buffer.from("second"), Buffer.from([0x00])];
  const result = await connection.produce("orders", 2, records);

  assert.equal(result.baseOffset, 0);
  assert.equal(result.logEndOffset, 3);
  assert.deepEqual(broker.recordsIn("orders", 2), records);
});

test("ack=none does not wait for an answer that never comes", async (t) => {
  // The broker sends nothing at all, and a client that awaits a response here hangs for ever.
  const { connection } = await fixture(t);

  const result = await Promise.race([
    connection.produce("orders", 0, [Buffer.from("x")], AckPolicy.NONE),
    new Promise((_, reject) => setTimeout(() => reject(new Error("waited for a response")), 3000)),
  ]);
  assert.equal(result, null, "AckPolicy.NONE has no offset to report");
});

test("a refusal is an error and keeps the connection", async (t) => {
  const { broker, connection } = await fixture(t);

  broker.refuseWith = Code.UNKNOWN_TOPIC_OR_PARTITION;
  await assert.rejects(() => connection.produce("nope", 0, [Buffer.from("x")]), BrokerError);

  // Framing was intact, so the connection is still usable — only a frame length out of range closes
  // one. A client that tore the socket down here would turn a refusal into an outage.
  broker.refuseWith = Code.NONE;
  assert.ok(await connection.produce("orders", 0, [Buffer.from("x")]));
});

test("metadata and key routing", async (t) => {
  const { connection } = await fixture(t);
  const topic = await connection.topic("orders");
  assert.equal(topic.partitions.length, 3);

  const key = Buffer.from("user-1");
  assert.equal(topic.partitionFor(key), topic.partitions[partitionFor(key, 3)]);
  // The same key, every time. This is what makes asking-then-sending safe with a key.
  assert.equal(topic.partitionFor(key), topic.partitionFor(key));
});

test("unkeyed routing advances round-robin", async (t) => {
  const { connection } = await fixture(t);
  const topic = await connection.topic("orders");
  const seen = Array.from({ length: 9 }, () => topic.partitionFor(null));

  const counts = new Map();
  for (const partition of seen) counts.set(partition, (counts.get(partition) ?? 0) + 1);
  assert.equal(counts.size, 3, `nine unkeyed records touched ${counts.size} partitions of 3`);
  for (const count of counts.values()) assert.equal(count, 3);
});

test("a truncated response is an error, not a decode crash", () => {
  // A response cut short by a broker restart is a connection problem, not a RangeError the caller
  // has never heard of coming out of a decode.
  assert.throws(() => decodeMetadata(Buffer.from([0, 0, 0, 1, 0, 5])), ProtocolError);
});
