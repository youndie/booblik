// Why the broker refused a request. Values are on the wire; see docs/api/protocol-wire.md §5.

export const Code = Object.freeze({
  NONE: 0,
  UNKNOWN_TOPIC_OR_PARTITION: 1,
  OFFSET_OUT_OF_RANGE: 2,
  RECORD_TOO_LARGE: 3,
  UNSUPPORTED_VERSION: 4,
  CORRUPT_REQUEST: 5,
});

const NAMES = Object.fromEntries(Object.entries(Code).map(([name, value]) => [value, name]));

export function codeName(code) {
  return NAMES[code] ?? `UNKNOWN(${code})`;
}

/**
 * A refusal from the broker, as opposed to anything going wrong with the connection.
 *
 * A refusal is a result and not a transport failure: the connection stays usable, because framing
 * was intact — the broker understood the request and declined it. Only a frame length outside the
 * allowed range closes a connection.
 */
export class BrokerError extends Error {
  constructor(code) {
    super(`booblik: broker refused the request: ${codeName(code)}`);
    this.name = "BrokerError";
    this.code = code;
    this.codeName = codeName(code);
  }
}

/** The bytes on the connection do not make sense — a bad length, a short response, a lost socket. */
export class ProtocolError extends Error {
  constructor(message) {
    super(`booblik: ${message}`);
    this.name = "ProtocolError";
  }
}

/**
 * A record whose bytes do not match the checksum stored with them.
 *
 * The client is the only party that can notice. On the zero-copy read path the broker sends segment
 * bytes to the socket without looking at them — that is what zero-copy means — so the sum is
 * computed once at write time to protect the **disk**, and verified once at read time, by whoever
 * finally holds the bytes. A client that skips it silently switches off the project's only defence
 * against a corrupted log.
 */
export class CorruptRecordError extends Error {
  constructor(offset, stored, computed) {
    super(
      `booblik: record at offset ${offset} fails its checksum: ` +
        `stored 0x${stored.toString(16).padStart(8, "0")}, ` +
        `computed 0x${computed.toString(16).padStart(8, "0")}`,
    );
    this.name = "CorruptRecordError";
    this.offset = offset;
    this.stored = stored;
    this.computed = computed;
  }
}

/**
 * The next record is larger than `maxBytes`, so it can never arrive whole.
 *
 * One of the two ways a consumer stalls, and the one that does not resolve itself. A response with
 * no whole records and a truncated tail means the record does not fit; a client that drops the tail
 * and retries makes exactly the same request for ever — running, reporting nothing, never
 * advancing. Thrown rather than retried, because raising `maxBytes` is the only fix.
 *
 * Not to be confused with `Code.RECORD_TOO_LARGE`, which is the broker refusing to **store** a
 * record too big for a segment. This one is the reader's own limit, chosen by the reader.
 */
export class RecordExceedsMaxBytesError extends Error {
  constructor(offset, recordBytes, maxBytes) {
    super(
      `booblik: record at offset ${offset} needs ${recordBytes} bytes and maxBytes is ${maxBytes}, ` +
        "so it can never be read whole",
    );
    this.name = "RecordExceedsMaxBytesError";
    this.offset = offset;
    this.recordBytes = recordBytes;
    this.maxBytes = maxBytes;
  }
}
