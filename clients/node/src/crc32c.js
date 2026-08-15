// CRC-32C (Castagnoli), specified in docs/api/protocol-wire.md §7.2.
//
// **Not `zlib.crc32`**, which Node grew in v20.15 and which is CRC-32 with polynomial 0x04C11DB7;
// this is 0x1EDC6F41. Both are called "CRC32", both take a buffer and return a 32-bit number, and
// substituting one for the other looks like nothing at all — the client simply rejects every record
// it reads. The check value for "123456789" is 0xE3069283; `zlib.crc32` gives 0xCBF43926.
//
// Node has no CRC-32C in the box, so here are the forty lines. A native addon would be faster, but
// this client has **no dependencies**, and a checksum is not where that rule gets its first
// exception: the point of verifying is that it happens everywhere, and an optional dependency makes
// verification optional.
//
// Two JavaScript-specific things:
//
//   - the table is built from the **reflected** polynomial 0x82F63B78, because this shifts right.
//     Using 0x1EDC6F41 with a right shift produces a stable, plausible, everywhere-wrong sum;
//   - `>>> 0` at the end is not cosmetic. Bitwise operators in JavaScript produce **signed** 32-bit
//     results, so the final XOR against 0xFFFFFFFF lands negative about half the time, and a
//     negative sum equals nothing the broker ever stored. This is the same class of trap as
//     `Math.imul` in the partitioner: the arithmetic here is not the arithmetic of a double.
//
// Pinned by conformance/vectors/crc32c.tsv, computed by an independent implementation.

/** The reflected form of 0x1EDC6F41. Mixing the two up is the silent way to get this wrong. */
const POLYNOMIAL = 0x82f63b78;

function buildTable() {
  const table = new Uint32Array(256);
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? (value >>> 1) ^ POLYNOMIAL : value >>> 1;
    }
    // Uint32Array stores it unsigned, which is also why the table is typed rather than a plain
    // array of numbers that would each carry a sign nobody wants.
    table[index] = value;
  }
  return table;
}

/** Built once at import: 256 entries, and rebuilding it per record would be most of the cost. */
const TABLE = buildTable();

/**
 * The CRC-32C of `data`, as an unsigned 32-bit number.
 *
 * `init` and `xorout` are both 0xFFFFFFFF, which is where the two XORs against it come from.
 *
 * @param {Uint8Array} data
 * @returns {number}
 */
export function crc32c(data) {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc = TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
