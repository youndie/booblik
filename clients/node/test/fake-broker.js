// A broker for tests: speaks the protocol well enough to answer, and **decodes what the client
// encoded** rather than pattern-matching bytes.
//
// That is the point of it. An encoding mistake becomes a decode failure here instead of a mystery
// against a real broker, and the test suite needs no Docker, no network and no fixtures. It is also
// a separate reading of docs/api/protocol-wire.md from the client it checks.

import net from "node:net";

import { crc32c } from "../src/crc32c.js";
import { Code } from "../src/errors.js";

const PRODUCE = 1;
const FETCH = 2;
const METADATA = 3;

export class FakeBroker {
  #server;
  #buffers = new WeakMap();

  constructor(server, partitions) {
    this.#server = server;
    this.partitions = partitions;
    this.produced = new Map();
    this.requests = 0;
    this.nextOffset = 0;
    this.refuseWith = Code.NONE;
    // Flips a bit in every stored checksum, which is what a damaged segment looks like from the
    // socket: the bytes arrive, and only the sum disagrees with them.
    this.corrupt = false;
    // The apiVersion and the decoded fields of the last request, so a test can assert what was
    // actually put on the wire rather than what the client meant to put there.
    this.lastVersion = null;
    this.lastFetch = null;

    server.on("connection", (socket) => {
      this.#buffers.set(socket, Buffer.alloc(0));
      socket.on("data", (chunk) => this.#onData(socket, chunk));
      socket.on("error", () => socket.destroy());
    });
  }

  static start(partitions = 3) {
    return new Promise((resolve) => {
      const server = net.createServer();
      server.listen(0, "127.0.0.1", () => resolve(new FakeBroker(server, partitions)));
    });
  }

  get address() {
    const { address, port } = this.#server.address();
    return `${address}:${port}`;
  }

  close() {
    return new Promise((resolve) => this.#server.close(resolve));
  }

  recordsIn(topic, partition) {
    return this.produced.get(`${topic} ${partition}`) ?? [];
  }

  #onData(socket, chunk) {
    let buffer = Buffer.concat([this.#buffers.get(socket), chunk]);

    for (;;) {
      if (buffer.length < 4) break;
      const length = buffer.readInt32BE(0);
      if (buffer.length < 4 + length) break;

      const frame = buffer.subarray(4, 4 + length);
      buffer = buffer.subarray(4 + length);

      const apiKey = frame.readInt16BE(0);
      this.lastVersion = frame.readInt16BE(2);
      const correlation = frame.readInt32BE(4);
      const payload = frame.subarray(8);

      let body = Buffer.alloc(0);
      let silent = false;
      if (apiKey === PRODUCE) {
        [body, silent] = this.#produce(payload);
      } else if (apiKey === FETCH) {
        body = this.#fetch(payload);
      } else if (apiKey === METADATA) {
        body = this.#metadata(payload);
      }
      if (silent) continue;

      const response = Buffer.allocUnsafe(4 + 6 + body.length);
      response.writeInt32BE(6 + body.length, 0);
      response.writeInt32BE(correlation, 4);
      response.writeInt16BE(this.refuseWith, 8);
      body.copy(response, 10);
      socket.write(response);
    }
    this.#buffers.set(socket, buffer);
  }

  // Returns the body and whether to stay silent — silence being what AckPolicy.NONE means on the
  // wire, and the behaviour a client most often gets wrong.
  #produce(payload) {
    let cursor = 0;
    const nameLength = payload.readUInt16BE(cursor);
    cursor += 2;
    const topic = payload.toString("utf8", cursor, cursor + nameLength);
    cursor += nameLength;

    const partition = payload.readInt32BE(cursor);
    cursor += 4;
    const ack = payload.readInt8(cursor);
    cursor += 1;
    const count = payload.readInt32BE(cursor);
    cursor += 4;

    const records = [];
    for (let index = 0; index < count; index += 1) {
      const size = payload.readInt32BE(cursor);
      cursor += 4;
      records.push(Buffer.from(payload.subarray(cursor, cursor + size)));
      cursor += size;
    }

    const base = this.nextOffset;
    if (this.refuseWith === Code.NONE) {
      const key = `${topic} ${partition}`;
      this.produced.set(key, [...(this.produced.get(key) ?? []), ...records]);
      this.nextOffset += records.length;
    }
    this.requests += 1;

    if (ack === 0) return [Buffer.alloc(0), true];

    const body = Buffer.allocUnsafe(16);
    body.writeBigInt64BE(BigInt(base), 0);
    body.writeBigInt64BE(BigInt(base + records.length), 8);
    return [body, false];
  }

  // Puts records in a partition's log without going through PRODUCE, so a fetch test states what
  // is there to read instead of arranging for it. Offsets in this fixture are indices into that
  // array, per partition.
  seed(topic, partition, ...records) {
    const key = `${topic} ${partition}`;
    this.produced.set(key, [...(this.produced.get(key) ?? []), ...records.map((r) => Buffer.from(r))]);
  }

  // Answers from the seeded log, framing records exactly as the disk holds them — payloadSize,
  // crc32c, payload — and cutting the response at maxBytes **in bytes**, which is what puts a
  // partial record at the end of a full response.
  //
  // maxWait is decoded and remembered, then ignored: nothing here can produce a record while a
  // request waits, so holding one would only make the tests slower.
  #fetch(payload) {
    let cursor = 0;
    const nameLength = payload.readUInt16BE(cursor);
    cursor += 2;
    const topic = payload.toString("utf8", cursor, cursor + nameLength);
    cursor += nameLength;

    this.lastFetch = {
      topic,
      partition: payload.readInt32BE(cursor),
      offset: Number(payload.readBigInt64BE(cursor + 4)),
      maxBytes: payload.readInt32BE(cursor + 12),
      maxWaitMillis: payload.readInt32BE(cursor + 16),
      minBytes: payload.readInt32BE(cursor + 20),
    };

    const log = this.recordsIn(topic, this.lastFetch.partition);
    const framed = [];
    for (const record of log.slice(this.lastFetch.offset)) {
      const header = Buffer.allocUnsafe(8);
      header.writeInt32BE(record.length, 0);
      // `>>> 0` on the damaged sum, and the fixture needed it before the client did: `^` yields a
      // signed 32-bit number, and writeUInt32BE refuses a negative one. The same operator is why
      // crc32c ends with the same shift.
      header.writeUInt32BE(this.corrupt ? (crc32c(record) ^ 1) >>> 0 : crc32c(record), 4);
      framed.push(header, record);
    }

    const stream = Buffer.concat(framed).subarray(0, this.lastFetch.maxBytes);
    const head = Buffer.allocUnsafe(12);
    head.writeBigInt64BE(BigInt(log.length), 0);
    head.writeInt32BE(stream.length, 8);
    return Buffer.concat([head, stream]);
  }

  #metadata(payload) {
    const count = payload.readInt32BE(0);
    let cursor = 4;
    const names = [];
    for (let index = 0; index < count; index += 1) {
      const nameLength = payload.readUInt16BE(cursor);
      cursor += 2;
      names.push(payload.toString("utf8", cursor, cursor + nameLength));
      cursor += nameLength;
    }
    if (names.length === 0) names.push("everything");

    const chunks = [Buffer.alloc(4)];
    chunks[0].writeInt32BE(names.length, 0);

    for (const name of names) {
      const encoded = Buffer.from(name, "utf8");
      const header = Buffer.allocUnsafe(2 + encoded.length + 4);
      header.writeUInt16BE(encoded.length, 0);
      encoded.copy(header, 2);
      header.writeInt32BE(this.partitions, 2 + encoded.length);
      chunks.push(header);

      for (let partition = 0; partition < this.partitions; partition += 1) {
        const info = Buffer.allocUnsafe(20);
        info.writeInt32BE(partition, 0);
        info.writeBigInt64BE(0n, 4);
        info.writeBigInt64BE(BigInt(this.nextOffset), 12);
        chunks.push(info);
      }
    }
    return Buffer.concat(chunks);
  }
}
