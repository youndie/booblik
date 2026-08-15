// Reading one partition, forward, from wherever it is told to start.
//
// The expensive half of a client, and none of what makes it expensive is visible in the protocol. A
// publisher that gets something wrong is told so by the broker; a consumer that gets something wrong
// returns plausible bytes, or returns nothing and calls it the end of the log.

import { crc32c } from "./crc32c.js";
import { CorruptRecordError, ProtocolError, RecordExceedsMaxBytesError } from "./errors.js";

/**
 * 1 MiB: large enough that a fetch is worth its round trip, small enough that one response cannot
 * dominate a small process. Every client in this repository uses the same number.
 */
export const DEFAULT_MAX_BYTES = 1 << 20;

/**
 * Five seconds. A caught-up consumer with no wait asks again immediately and gets nothing, which is
 * a busy loop dressed as a poll — measured at about two thousand pointless requests a second
 * (benchmarking, measurement 24). Waiting costs new records nothing: the broker answers the moment
 * one lands, not when the timer runs out.
 */
export const DEFAULT_MAX_WAIT_MILLIS = 5_000;

// payloadSize and crc32c, in front of every record — the on-disk format unchanged, which is what
// lets the broker send segment bytes without touching them.
const RECORD_HEADER_BYTES = 8;

/**
 * Unframes a FETCH response body and verifies every checksum.
 *
 * `offset` is the one that was asked for, and it is here only so a failure can say *which* record
 * is damaged rather than that one of them is.
 */
export function decodeFetch(body, offset) {
  if (body.length < 12) {
    throw new ProtocolError(`FETCH response is ${body.length} bytes, expected at least 12`);
  }

  const highWatermark = Number(body.readBigInt64BE(0));
  const promised = body.readInt32BE(8);
  const payload = body.subarray(12);

  // The frame length already bounds the payload, so this field is redundant — which is exactly what
  // makes it worth checking. It is computed before the transfer starts, while the bytes arrive
  // afterwards from `transferTo` in an unpredictable number of pieces; a disagreement means the two
  // halves of the response came from different states of the log.
  if (promised !== payload.length) {
    throw new ProtocolError(
      `FETCH promised ${promised} payload bytes and the frame carries ${payload.length}`,
    );
  }

  const records = [];
  let cursor = 0;

  while (payload.length - cursor >= RECORD_HEADER_BYTES) {
    const size = payload.readInt32BE(cursor);
    // readUInt32BE and **not** readInt32BE: the sum is unsigned and half of all values have the
    // high bit set, which a signed read turns negative — after which it matches nothing.
    const stored = payload.readUInt32BE(cursor + 4);

    // A whole header is either there or not — parsing always resumes on a record boundary — so a
    // non-positive size is a malformed frame rather than a truncated tail. Empty records cannot be
    // stored at all, which is why the broker refuses them.
    if (size <= 0) {
      throw new ProtocolError(`record header at offset ${offset + records.length} says ${size} bytes`);
    }
    if (size > payload.length - cursor - RECORD_HEADER_BYTES) {
      return { highWatermark, records, truncated: true, truncatedRecordBytes: size };
    }

    const start = cursor + RECORD_HEADER_BYTES;
    // Copied out rather than subarrayed: a view keeps the whole response buffer alive behind one
    // record, so a caller holding one record out of a megabyte would hold the megabyte.
    const record = Buffer.from(payload.subarray(start, start + size));

    // After the length check and never before it: a truncated tail is not corruption, and reporting
    // it as such would turn the most ordinary response there is into an alarm.
    const computed = crc32c(record);
    if (computed !== stored) {
      throw new CorruptRecordError(offset + records.length, stored, computed);
    }
    records.push(record);
    cursor = start + size;
  }

  // Fewer bytes left than a record header: the response stopped inside the header of the next
  // record, which is the same truncation with nothing to say about its size.
  return { highWatermark, records, truncated: cursor < payload.length, truncatedRecordBytes: 0 };
}

/**
 * Reads one partition of one topic.
 *
 * **The position lives here, not in the broker.** That is half the reason this project has no
 * consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
 * already knows, and asking a broker to remember it is what drags in cluster consensus. The cost is
 * that a restarting consumer has to be told where to resume — `position` is the number to write
 * down, and writing it down *after* the records are dealt with rather than before is what makes a
 * restart re-deliver instead of skip.
 *
 * **Not safe for concurrent use.** Every `poll` advances the position, and the connection matches
 * responses to requests in the order they were sent. One consumer, one partition, one caller.
 */
export class Consumer {
  constructor(connection, topic, partition, start = 0, options = {}) {
    this.connection = connection;
    this.topic = topic;
    this.partition = partition;
    /** The offset of the next record this consumer will read. This is the number to persist. */
    this.position = start;
    /**
     * Where the log ended at the last successful poll, and 0 before the first one. A snapshot
     * rather than a live number: by the time it is read, the log may have grown.
     */
    this.highWatermark = 0;
    this.maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;
    this.maxWaitMillis = options.maxWaitMillis ?? DEFAULT_MAX_WAIT_MILLIS;
    this.minBytes = options.minBytes ?? 0;
  }

  /** Moves the read position. Anything fetched and not yet returned is simply forgotten. */
  seek(offset) {
    this.position = offset;
  }

  /** How many records this consumer was behind at the last poll. Same snapshot caveat. */
  get lag() {
    return Math.max(0, this.highWatermark - this.position);
  }

  /**
   * Reads the next records and advances `position` past them.
   *
   * **An empty array is not the end of anything.** A consumer that has caught up polls at the high
   * watermark and is answered with no records, which is the steady state of every consumer keeping
   * up; treating it as the end of the log is how a consumer stops for ever without erroring.
   *
   * The position advances past whole records only. A response can stop inside a record, because
   * `maxBytes` cuts on a byte boundary; the partial tail is dropped and the next poll asks for that
   * record again from its start. The broker will not do it for us — finding the record boundary
   * means parsing the batch, which is the work the zero-copy read path exists to avoid.
   *
   * Rejects with `RecordExceedsMaxBytesError` when nothing whole came back and something partial
   * did: the next record is larger than this consumer is willing to receive, so retrying is what a
   * stall looks like from the inside.
   */
  async poll() {
    const answer = await this.connection.fetch(this.topic, this.partition, this.position, {
      maxBytes: this.maxBytes,
      maxWaitMillis: this.maxWaitMillis,
      minBytes: this.minBytes,
    });

    if (answer.records.length === 0 && answer.truncated) {
      throw new RecordExceedsMaxBytesError(
        this.position,
        answer.truncatedRecordBytes,
        this.maxBytes,
      );
    }

    this.highWatermark = answer.highWatermark;
    this.position += answer.records.length;
    return answer.records;
  }

  /**
   * `poll` as an async iterator, yielding records one at a time and fetching as it goes:
   *
   *     for await (const record of consumer) {
   *       await handle(record);
   *     }
   *
   * **The loop does not end**, and that is the shape of the thing rather than an oversight: a
   * partition has no end, only a place it has not been written to yet. `break` out of it, or close
   * the connection; a fetch that fails throws out of the loop as any other await would.
   *
   * An async iterator and not an `on("record")` event: an emitter would push records at whatever
   * rate they arrive with no way for the handler to say it is not ready, so the position would run
   * ahead of what has actually been processed — and an error inside a listener has nowhere to go.
   * `for await` applies back-pressure by construction, because the next fetch does not happen until
   * the body of the loop is done.
   *
   * The position advances a whole fetch at a time, not a record at a time. Breaking out mid-batch
   * and persisting `position` skips the rest of that batch, so persist after the loop, or count
   * what was handled.
   */
  async *records() {
    for (;;) {
      for (const record of await this.poll()) yield record;
    }
  }

  [Symbol.asyncIterator]() {
    return this.records();
  }
}
