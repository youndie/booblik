/**
 * A client for booblik, a message broker on an append-only log.
 *
 * Speaks PRODUCE, FETCH and METADATA. The two halves are not the same size: a publisher that gets
 * something wrong is told so by the broker, while a consumer that gets something wrong returns
 * plausible bytes or stops quietly, so the reading half carries the checksum, the truncated tail
 * and the position.
 *
 * The protocol is docs/api/protocol-wire.md; where this disagrees with it, this is wrong.
 */

export { AckPolicy, Connection, Topic } from "./connection.js";
export { Consumer, DEFAULT_MAX_BYTES, DEFAULT_MAX_WAIT_MILLIS, decodeFetch } from "./consumer.js";
export { crc32c } from "./crc32c.js";
export {
  BrokerError,
  Code,
  CorruptRecordError,
  ProtocolError,
  RecordExceedsMaxBytesError,
  codeName,
} from "./errors.js";
export { fnv1a32, partitionFor } from "./partition.js";
export { OFFSET_UNKNOWN, Producer } from "./producer.js";
