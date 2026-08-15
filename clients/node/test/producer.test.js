import assert from "node:assert/strict";
import { setTimeout as sleep } from "node:timers/promises";
import { test } from "node:test";

import { AckPolicy, Connection } from "../src/connection.js";
import { Code } from "../src/errors.js";
import { OFFSET_UNKNOWN, Producer } from "../src/producer.js";
import { FakeBroker } from "./fake-broker.js";

async function fixture(t, config) {
  const broker = await FakeBroker.start(3);
  const connection = await Connection.connect(broker.address);
  const producer = new Producer(connection, config);

  t.after(async () => {
    await producer.close().catch(() => {});
    connection.close();
    await broker.close();
  });
  return { broker, connection, producer };
}

test("no records are lost across linger windows", async (t) => {
  // The regression test the JVM client needed twice, ported before it was needed here. Records
  // arrive one per window, so each is delivered by the timer rather than by a full batch — the
  // interleaving where the JVM accumulator lost a record, its timeout having cancelled the pending
  // receive and the cancelled receive having swallowed it.
  const linger = 1;
  const rounds = 300;
  const { broker, producer } = await fixture(t, { linger, maxBatchSize: 100 });

  const promises = [];
  for (let index = 0; index < rounds; index += 1) {
    promises.push(producer.send("orders", 0, Buffer.from(`r-${index}`)));
    // Sleeping the window, not less: without this the records pile into full batches and the
    // interleaving this test is named after never happens.
    await sleep(linger);
  }

  const offsets = await Promise.all(promises);
  offsets.forEach((offset, index) => assert.equal(offset, index, `record ${index}`));
  assert.equal(broker.recordsIn("orders", 0).length, rounds);

  // And the interleaving actually happened. The bound is derived rather than chosen: a batch-driven
  // run would take exactly rounds/maxBatchSize requests, and anything above that is the timer. A
  // larger threshold measures the host's timer granularity instead.
  assert.ok(
    broker.requests > rounds / 100,
    `${rounds} records went out in ${broker.requests} requests, so the window never fired`,
  );
});

test("a full batch does not wait for the window", async (t) => {
  // An hour of linger is still pending; only the batch being full can settle this.
  const { broker, producer } = await fixture(t, { linger: 3_600_000, maxBatchSize: 10 });

  const promises = Array.from({ length: 10 }, (_, index) =>
    producer.send("orders", 0, Buffer.from(`r-${index}`)),
  );
  assert.equal(await promises.at(-1), 9);
  assert.equal(broker.requests, 1);
});

test("partitions accumulate separately", async (t) => {
  // A request addresses one partition — a partition being what has one writer.
  const { broker, producer } = await fixture(t, { linger: 3_600_000, maxBatchSize: 100 });

  const promises = [0, 1, 2].map((partition) =>
    producer.send("orders", partition, Buffer.from("x")),
  );
  await producer.flush();
  await Promise.all(promises);

  for (const partition of [0, 1, 2]) {
    assert.equal(broker.recordsIn("orders", partition).length, 1);
  }
  assert.equal(broker.requests, 3);
});

test("close flushes what is queued", async (t) => {
  // Dropping queued records would make every clean shutdown a silent data loss.
  const { broker, producer } = await fixture(t, { linger: 3_600_000, maxBatchSize: 100 });

  const promise = producer.send("orders", 0, Buffer.from("x"));
  await producer.close();

  assert.equal(await promise, 0);
  assert.equal(broker.recordsIn("orders", 0).length, 1);
  await assert.rejects(() => producer.send("orders", 0, Buffer.from("y")));
});

test("ack=none settles with an unknown offset", async (t) => {
  // Nothing is ever going to settle this from the wire, so it has to say so rather than hanging.
  const { producer } = await fixture(t, { linger: 1, maxBatchSize: 100, ack: AckPolicy.NONE });
  assert.equal(await producer.send("orders", 0, Buffer.from("x")), OFFSET_UNKNOWN);
});

test("a broker failure reaches every waiting caller", async (t) => {
  // A batch that fails fails for all of its records. Settling some and abandoning the rest would
  // leave callers awaiting a promise nothing will ever touch.
  const { broker, producer } = await fixture(t, { linger: 1, maxBatchSize: 100 });
  broker.refuseWith = Code.RECORD_TOO_LARGE;

  const promises = Array.from({ length: 5 }, () => producer.send("orders", 0, Buffer.from("x")));
  for (const promise of promises) await assert.rejects(() => promise);
});
