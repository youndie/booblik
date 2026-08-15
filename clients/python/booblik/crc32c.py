"""CRC-32C (Castagnoli), specified in docs/api/protocol-wire.md §7.2.

**Not** ``zlib.crc32``. That is CRC-32 with polynomial ``0x04C11DB7``; this is ``0x1EDC6F41``. Both
are called "CRC32", both take bytes and return a 32-bit number, and nothing about substituting one
for the other looks wrong — the client simply rejects every record it reads, or accepts every
damaged one, depending on which side of the comparison the mistake lands. The check value for
``b"123456789"`` is ``0xE3069283``; ``zlib.crc32`` gives ``0xCBF43926`` for the same input.

Python has no CRC-32C in the standard library, so here are the forty lines. Google's ``crc32c``
package would be faster — it uses the SSE4.2 instruction — but this client has **no dependencies**,
and a checksum is not where that rule gets its first exception: the whole point of verifying is that
it happens everywhere, and an optional dependency makes verification optional.

Two things about the implementation:

* the table is built from the **reflected** polynomial ``0x82F63B78``, because this shifts right.
  Using ``0x1EDC6F41`` with a right shift produces a stable, plausible, everywhere-wrong sum;
* nothing here needs the 32-bit mask that :func:`booblik.partition.fnv1a32` cannot do without,
  and that is worth saying rather than leaving to be re-derived: this form only shifts right and
  XORs values already inside 32 bits, so Python's unbounded integers have nowhere to grow. The
  place where the width does bite is on the other side — the stored sum is unpacked from a wire
  field that ``struct`` reads as **signed**, and comparing it without masking fails on every record
  whose checksum has the high bit set, which is about half of them.

Pinned by conformance/vectors/crc32c.tsv, computed by an independent implementation.
"""

from __future__ import annotations

#: The reflected form of 0x1EDC6F41. Mixing the two up is the silent way to get this wrong.
POLYNOMIAL = 0x82F63B78

MASK32 = 0xFFFFFFFF


def _build_table() -> tuple[int, ...]:
    table = []
    for index in range(256):
        value = index
        for _ in range(8):
            value = (value >> 1) ^ (POLYNOMIAL if value & 1 else 0)
        table.append(value)
    return tuple(table)


#: Built once at import. 256 entries, and rebuilding it per record would be most of the cost of
#: checking a small one.
TABLE = _build_table()


def crc32c(data: bytes) -> int:
    """The CRC-32C of ``data``, as an unsigned 32-bit int.

    ``init`` and ``xorout`` are both ``0xFFFFFFFF``, which is where the two ``^ MASK32`` come from.
    """
    crc = MASK32
    for byte in data:
        crc = TABLE[(crc ^ byte) & 0xFF] ^ (crc >> 8)
    return crc ^ MASK32
