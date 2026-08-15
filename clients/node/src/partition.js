// The partitioner, specified in docs/api/protocol-wire.md §7 and pinned by the golden vectors in
// conformance/vectors/partitioner-fnv1a.tsv.
//
// It matters more than its size suggests. The broker never sees the key — the record format has no
// room for one — so the client picks the partition and sends the number. Two publishers that hash a
// key differently put it in two partitions, and per-key order, which is what partitions are for, is
// gone. Nothing errors when that happens: the record lands in a partition that exists, just not the
// same one.

export const FNV_OFFSET_BASIS = 0x811c9dc5;
export const FNV_PRIME = 0x01000193;

/**
 * The 32-bit FNV-1a hash of `key`, as an unsigned number.
 *
 * **`Math.imul` is not a micro-optimisation — it is the algorithm.** JavaScript numbers are
 * doubles, so a plain `hash * FNV_PRIME` leaves exact integer range after about three bytes and
 * silently starts rounding: the result stays a plausible number and agrees with nothing. `Math.imul`
 * is 32-bit multiplication with the wraparound FNV-1a is defined in. This is JavaScript's version of
 * the trap that costs Java and Kotlin a cast and Python a mask.
 *
 * `>>> 0` at the end because `Math.imul` yields a signed 32-bit result and the specification's fold
 * is unsigned.
 *
 * @param {Uint8Array} key
 * @returns {number}
 */
export function fnv1a32(key) {
  let hash = FNV_OFFSET_BASIS;
  for (const byte of key) {
    // Iterating a Uint8Array yields unsigned bytes, so the XOR needs nothing extra.
    hash = Math.imul(hash ^ byte, FNV_PRIME);
  }
  return hash >>> 0;
}

/**
 * Folds the hash of `key` into `[0, partitions)`.
 *
 * Unsigned remainder, which is why the specification never has to say how a language signs its
 * integers. Throws rather than returning something for a non-positive count: there is no partition
 * to pick, and returning 0 would send records to one that may not exist.
 *
 * @param {Uint8Array} key
 * @param {number} partitions
 * @returns {number}
 */
export function partitionFor(key, partitions) {
  if (!Number.isInteger(partitions) || partitions <= 0) {
    throw new RangeError("partitionFor needs at least one partition");
  }
  return fnv1a32(key) % partitions;
}
