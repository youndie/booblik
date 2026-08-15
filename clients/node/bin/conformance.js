#!/usr/bin/env node
//
// This client under test, driven by conformance/harness.
//
// The contract is in conformance/README.md: verbs on argv, `key=value` on stdout, the broker in
// BOOBLIK_BROKER. Exit 0 means the verb was carried out — **including** when the broker refused it,
// which is a result and is reported as `error=CODE`. A non-zero exit means this program failed.
//
// Declares `producer,consumer`.

import process from "node:process";

import { AckPolicy, Connection } from "../src/connection.js";
import { BrokerError } from "../src/errors.js";

const ACK = { none: AckPolicy.NONE, written: AckPolicy.WRITTEN, forced: AckPolicy.FORCED };

async function main([verb, ...args]) {
  if (verb === undefined) {
    process.stderr.write("usage: conformance.js <verb> [args...]  (broker in BOOBLIK_BROKER)\n");
    return 2;
  }

  if (verb === "capabilities") {
    process.stdout.write("roles=producer,consumer\nname=node\n");
    return 0;
  }

  const address = process.env.BOOBLIK_BROKER;
  if (!address) {
    process.stderr.write("BOOBLIK_BROKER is not set (host:port)\n");
    return 2;
  }

  const connection = await Connection.connect(address, { timeout: 15_000 });
  try {
    if (verb === "metadata") {
      await metadata(connection, args[0]);
    } else if (verb === "produce") {
      await produce(connection, args[0], Number(args[1]), args[2], args[3]);
    } else if (verb === "produce-keyed") {
      await produceKeyed(connection, args[0], args[1], args[2]);
    } else if (verb === "fetch") {
      await fetch(connection, args[0], Number(args[1]), Number(args[2]), Number(args[3]));
    } else {
      process.stderr.write(`unknown verb: ${verb}\n`);
      return 2;
    }
  } catch (failure) {
    // A refusal is a result, not a failure of this program: report it and exit zero.
    if (failure instanceof BrokerError) {
      process.stdout.write(`error=${failure.codeName}\n`);
    } else {
      throw failure;
    }
  } finally {
    connection.close();
  }
  return 0;
}

async function metadata(connection, topic) {
  for (const info of (await connection.metadata(topic)).get(topic) ?? []) {
    process.stdout.write(`partition=${info.partition} ${info.logStartOffset} ${info.highWatermark}\n`);
  }
}

async function produce(connection, topic, partition, ack, records) {
  // An empty field is a zero-length record and it is passed on rather than tidied away: the broker
  // refuses those, and the harness checks that the refusal reaches the caller.
  const payloads = records.split(",").map((field) => Buffer.from(field, "hex"));

  const result = await connection.produce(topic, partition, payloads, ACK[ack]);
  // Null under AckPolicy.NONE, and printing nothing is the correct answer: no offset exists yet.
  // Awaiting one here is what the harness times out on.
  if (result !== null) {
    process.stdout.write(`baseOffset=${result.baseOffset}\nlogEndOffset=${result.logEndOffset}\n`);
  }
}

// Where the partitioner is exercised for real: the partition is chosen here, from the key, because
// the broker never sees the key at all.
async function produceKeyed(connection, name, keyHex, payloadHex) {
  const key = Buffer.from(keyHex, "hex");
  const topic = await connection.topic(name);
  const chosen = topic.partitionFor(key);

  const result = await connection.produce(name, chosen, [Buffer.from(payloadHex, "hex")]);
  process.stdout.write(`partition=${chosen}\nbaseOffset=${result.baseOffset}\n`);
}

// The raw call and not a Consumer: the checks are about what one FETCH answers — a truncated tail,
// an empty response at the high watermark, a refusal past the end — and a Consumer would smooth over
// exactly those by advancing a position and waiting.
async function fetch(connection, topic, partition, offset, maxBytes) {
  const answer = await connection.fetch(topic, partition, offset, { maxBytes });
  process.stdout.write(`highWatermark=${answer.highWatermark}\n`);

  // Nothing whole and something partial: the next record is bigger than maxBytes and never arrives,
  // so a caller that only sees an empty list cannot tell this from having caught up.
  if (answer.records.length === 0 && answer.truncated) {
    process.stdout.write(`recordExceedsMaxBytes=${answer.truncatedRecordBytes}\n`);
    return;
  }

  for (const record of answer.records) {
    process.stdout.write(`record=${record.toString("hex")}\n`);
  }
}

main(process.argv.slice(2))
  .then((code) => process.exit(code))
  .catch((failure) => {
    process.stderr.write(`${failure.message}\n`);
    process.exit(1);
  });
