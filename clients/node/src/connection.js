// The connection: framing, PRODUCE, FETCH and METADATA.
//
// Responses arrive in the order the requests went out and carry the correlation id back, so this
// keeps a FIFO of waiting callers rather than a map. That also means the connection is naturally
// pipelined: a second request may go out before the first is answered.

import net from "node:net";

import { Consumer, decodeFetch } from "./consumer.js";
import { BrokerError, Code, ProtocolError } from "./errors.js";
import { partitionFor } from "./partition.js";

export const AckPolicy = Object.freeze({
  /**
   * Answers nothing at all — not an empty response, nothing, because no offset exists until the
   * writer reaches the batch. It is also the only mode in which the broker may lose an accepted
   * record without the client hearing about it, or about being overloaded.
   */
  NONE: 0,
  /** Answers once the record is in the log, before any durability barrier. */
  WRITTEN: 1,
  /** Answers after force(). Not one barrier per request: the broker groups everyone queued. */
  FORCED: 2,
});

const PRODUCE = 1;
const FETCH = 2;
const METADATA = 3;
const VERSION = 1;
// FETCH alone has a v2, which adds maxWaitMillis and minBytes. PRODUCE and METADATA have no v2.
const FETCH_VERSION = 2;

// What the length prefix counts before the payload: apiKey, apiVersion, correlationId.
const REQUEST_HEADER_BYTES = 2 + 2 + 4;
// correlationId and errorCode, on every response.
const RESPONSE_HEADER_BYTES = 4 + 2;

const MAX_FRAME_BYTES = 8 << 20;

/**
 * One connection to a broker.
 *
 * Offsets are returned as numbers. A log would need more than 2^53 records for that to lose
 * precision, which is not a quantity this broker can reach; BigInt everywhere would be a tax on
 * every caller for a limit nobody meets.
 */
export class Connection {
  #socket;
  #buffer = Buffer.alloc(0);
  #pending = [];
  #correlation = 0;
  #failure = null;

  constructor(socket) {
    this.#socket = socket;
    socket.setNoDelay(true);
    socket.on("data", (chunk) => this.#onData(chunk));
    socket.on("error", (error) => this.#fail(error));
    socket.on("close", () => this.#fail(new ProtocolError("connection closed by the broker")));
  }

  /** @param {string} address `host:port`, which is the form every configuration uses. */
  static connect(address, { timeout = 30_000 } = {}) {
    const separator = address.lastIndexOf(":");
    const host = address.slice(0, separator);
    const port = Number(address.slice(separator + 1));

    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ host, port });
      socket.setTimeout(timeout);

      const onError = (error) => {
        socket.destroy();
        reject(error);
      };
      socket.once("error", onError);
      socket.once("timeout", () => onError(new ProtocolError(`connecting to ${address} timed out`)));
      socket.once("connect", () => {
        socket.setTimeout(0);
        socket.removeListener("error", onError);
        resolve(new Connection(socket));
      });
    });
  }

  close() {
    this.#socket.destroy();
  }

  // -- framing -----------------------------------------------------------------------------------

  #onData(chunk) {
    this.#buffer = this.#buffer.length === 0 ? chunk : Buffer.concat([this.#buffer, chunk]);

    for (;;) {
      if (this.#buffer.length < 4) return;
      const length = this.#buffer.readInt32BE(0);
      if (length < RESPONSE_HEADER_BYTES || length > MAX_FRAME_BYTES) {
        this.#fail(new ProtocolError(`response frame length ${length} is out of range`));
        return;
      }
      if (this.#buffer.length < 4 + length) return;

      const frame = this.#buffer.subarray(4, 4 + length);
      this.#buffer = this.#buffer.subarray(4 + length);
      this.#answer(frame);
    }
  }

  #answer(frame) {
    const correlation = frame.readInt32BE(0);
    const code = frame.readInt16BE(4);

    const waiting = this.#pending.shift();
    if (waiting === undefined) return;

    // Checked rather than trusted. A response carrying somebody else's correlation id does not
    // merely lose information — it **resolves the wrong request**, handing one caller another
    // caller's offsets.
    if (waiting.correlation !== correlation) {
      waiting.reject(new ProtocolError(`response ${correlation} answered request ${waiting.correlation}`));
      return;
    }
    if (code !== Code.NONE) {
      waiting.reject(new BrokerError(code));
      return;
    }
    // Copied out: a subarray keeps the whole received chunk alive behind it.
    waiting.resolve(Buffer.from(frame.subarray(RESPONSE_HEADER_BYTES)));
  }

  #fail(error) {
    if (this.#failure) return;
    this.#failure = error;
    for (const waiting of this.#pending.splice(0)) waiting.reject(error);
  }

  #send(apiKey, version, payload) {
    if (this.#failure) throw this.#failure;

    const correlation = ++this.#correlation;
    const frameLength = REQUEST_HEADER_BYTES + payload.length;

    const frame = Buffer.allocUnsafe(4 + frameLength);
    frame.writeInt32BE(frameLength, 0);
    frame.writeInt16BE(apiKey, 4);
    frame.writeInt16BE(version, 6);
    frame.writeInt32BE(correlation, 8);
    payload.copy(frame, 12);

    this.#socket.write(frame);
    return correlation;
  }

  #expect(correlation) {
    return new Promise((resolve, reject) => {
      if (this.#failure) {
        reject(this.#failure);
        return;
      }
      this.#pending.push({ correlation, resolve, reject });
    });
  }

  // -- requests ----------------------------------------------------------------------------------

  /**
   * Which topics exist and where each partition currently starts and ends. No names asks for
   * everything.
   *
   * A named topic that does not exist fails the whole request with UNKNOWN_TOPIC_OR_PARTITION
   * rather than being left out of the answer — otherwise "no such topic" and "the topic is empty"
   * would arrive looking identical.
   */
  async metadata(...topics) {
    const names = topics.map((topic) => Buffer.from(topic, "utf8"));
    const size = 4 + names.reduce((total, name) => total + 2 + name.length, 0);

    const payload = Buffer.allocUnsafe(size);
    let offset = payload.writeInt32BE(names.length, 0);
    for (const name of names) {
      offset = payload.writeUInt16BE(name.length, offset);
      offset += name.copy(payload, offset);
    }

    return decodeMetadata(await this.#expectAfter(METADATA, VERSION, payload));
  }

  /**
   * Appends records to one partition as a single request.
   *
   * They land **contiguously** — one request is written by one call into that partition's writer,
   * so the offsets run from `baseOffset` with nothing interleaved. It is **not** atomic: a crash
   * mid-write leaves whatever reached the disk, and recovery keeps the prefix that passes its
   * checksums. There are no transactions in this broker.
   *
   * Resolves to `null` under `AckPolicy.NONE`, because no answer is coming.
   *
   * Nothing here validates record sizes. The broker refuses empty records and empty batches with
   * CORRUPT_REQUEST, and duplicating that rule client-side would create a second place to disagree
   * with it — the rule belongs to whatever has to store the result.
   */
  async produce(topic, partition, records, ack = AckPolicy.WRITTEN) {
    const name = Buffer.from(topic, "utf8");
    const bodies = records.map((record) => Buffer.from(record));

    const size =
      2 + name.length + 4 + 1 + 4 + bodies.reduce((total, body) => total + 4 + body.length, 0);
    const payload = Buffer.allocUnsafe(size);

    let offset = payload.writeUInt16BE(name.length, 0);
    offset += name.copy(payload, offset);
    offset = payload.writeInt32BE(partition, offset);
    offset = payload.writeInt8(ack, offset);
    offset = payload.writeInt32BE(bodies.length, offset);
    for (const body of bodies) {
      offset = payload.writeInt32BE(body.length, offset);
      offset += body.copy(payload, offset);
    }

    const correlation = this.#send(PRODUCE, VERSION, payload);
    if (ack === AckPolicy.NONE) return null;

    const body = await this.#expect(correlation);
    return {
      baseOffset: Number(body.readBigInt64BE(0)),
      logEndOffset: Number(body.readBigInt64BE(8)),
    };
  }

  /**
   * Reads records from one partition, checksum-verified.
   *
   * `maxBytes` bounds the response **in bytes, not in records**, so it can stop inside one; see
   * `truncated` on the result. `maxWaitMillis` is how long the broker may hold a request that has
   * nothing to answer with, and 0 — the default here, though not in `Consumer` — returns at once.
   *
   * `minBytes` greater than `maxBytes` is CORRUPT_REQUEST: a request that can never be satisfied.
   * That is left to the broker rather than checked here, so there is one place that decides it
   * instead of two that can disagree.
   *
   * Always v2, including when nothing is being waited for. One code path rather than two: a client
   * that switched versions depending on its arguments would exercise v1 only in the branch nobody
   * debugs. The broker still decodes v1 for anybody else's client.
   */
  async fetch(topic, partition, offset, { maxBytes = 1 << 20, maxWaitMillis = 0, minBytes = 0 } = {}) {
    const name = Buffer.from(topic, "utf8");
    const payload = Buffer.allocUnsafe(2 + name.length + 4 + 8 + 4 + 4 + 4);

    let cursor = payload.writeUInt16BE(name.length, 0);
    cursor += name.copy(payload, cursor);
    cursor = payload.writeInt32BE(partition, cursor);
    cursor = payload.writeBigInt64BE(BigInt(offset), cursor);
    cursor = payload.writeInt32BE(maxBytes, cursor);
    cursor = payload.writeInt32BE(maxWaitMillis, cursor);
    payload.writeInt32BE(minBytes, cursor);

    const body = await this.#expectAfter(FETCH, FETCH_VERSION, payload);
    return decodeFetch(body, offset);
  }

  /**
   * A `Consumer` reading this partition from `start`.
   *
   * Reading "from the beginning" means starting at the partition's `logStartOffset` from
   * `metadata`, not at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has ever dropped a
   * segment to retention. Reading "only what is new" means its `highWatermark`.
   */
  consumer(topic, partition, start = 0, options = {}) {
    return new Consumer(this, topic, partition, start, options);
  }

  /**
   * A handle with the topic's partitions taken from the broker rather than from an argument.
   *
   * Asking is the point. A partition count supplied by hand can disagree with the broker, and what
   * that produces — records piling into the partitions that exist while others are never written
   * to — reads as a data problem rather than the configuration mistake it is.
   */
  async topic(name) {
    const answer = await this.metadata(name);
    const partitions = (answer.get(name) ?? []).map((info) => info.partition);
    if (partitions.length === 0) throw new ProtocolError(`broker has no partitions for ${name}`);
    return new Topic(this, name, partitions);
  }

  async #expectAfter(apiKey, version, payload) {
    return this.#expect(this.#send(apiKey, version, payload));
  }
}

/** One topic, so its name and its routing stop being arguments to every call. */
export class Topic {
  #roundRobin = 0;

  constructor(connection, name, partitions) {
    this.connection = connection;
    this.name = name;
    this.partitions = partitions;
  }

  /**
   * Where a record with this key goes.
   *
   * A null key takes the next partition round-robin, and that counter advances on every call — so
   * asking and then sending is two turns of it and the records start skipping partitions. With a
   * key there is no such thing, the answer being a pure function of the key. Round-robin rather
   * than random because a handful of unkeyed records should look evenly spread, and random visibly
   * clumps at small counts in a way that reads as a bug.
   */
  partitionFor(key) {
    if (key === null || key === undefined) {
      return this.partitions[this.#roundRobin++ % this.partitions.length];
    }
    return this.partitions[partitionFor(key, this.partitions.length)];
  }

  /**
   * Publishes one record, choosing its partition from `key`.
   *
   * One record per request throws away the largest single performance factor there is: the broker's
   * own measurements put batches of a hundred at 4 335 482 records/s against 80 592 one at a time.
   * Use `Connection.produce` with an array, or a Producer, whenever records are available together.
   */
  send(record, key = null, ack = AckPolicy.WRITTEN) {
    return this.connection.produce(this.name, this.partitionFor(key), [record], ack);
  }
}

export function decodeMetadata(body) {
  const result = new Map();
  let cursor = 0;

  try {
    const topicCount = body.readInt32BE(cursor);
    cursor += 4;

    for (let index = 0; index < topicCount; index += 1) {
      const nameLength = body.readUInt16BE(cursor);
      cursor += 2;
      const name = body.toString("utf8", cursor, cursor + nameLength);
      cursor += nameLength;

      const partitionCount = body.readInt32BE(cursor);
      cursor += 4;

      const partitions = [];
      for (let partition = 0; partition < partitionCount; partition += 1) {
        partitions.push({
          partition: body.readInt32BE(cursor),
          logStartOffset: Number(body.readBigInt64BE(cursor + 4)),
          highWatermark: Number(body.readBigInt64BE(cursor + 12)),
        });
        cursor += 20;
      }
      result.set(name, partitions);
    }
  } catch (failure) {
    // A response cut short by a broker restart is a connection problem, not a RangeError out of a
    // decode that the caller has never heard of.
    throw new ProtocolError(`METADATA response ends early: ${failure.message}`);
  }
  return result;
}
