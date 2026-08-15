"""The two algorithms that never appear on the wire and must still agree between any two clients.

One Python implementation, used by both the vector generator and the harness. Independence from the
*Kotlin* client is what makes the vectors worth having, and that is unaffected by these two Python
callers sharing code — splitting them would give two chances to write the same bug instead of one.

Specified in `docs/api/protocol-wire.md` §7.
"""

MASK32 = 0xFFFFFFFF

FNV_OFFSET_BASIS = 0x811C9DC5
FNV_PRIME = 0x01000193

# Reflected Castagnoli. The polynomial is 0x1EDC6F41; 0x82F63B78 is its bit reversal, which is what
# a right-shifting table implementation uses. Confusing the two produces a CRC that is stable,
# plausible, and wrong everywhere — so the published check value is asserted at import.
CRC32C_POLY_REFLECTED = 0x82F63B78

CRC32C_TABLE = []
for _i in range(256):
    _c = _i
    for _ in range(8):
        _c = (_c >> 1) ^ (CRC32C_POLY_REFLECTED if _c & 1 else 0)
    CRC32C_TABLE.append(_c)


def fnv1a32(data: bytes) -> int:
    """FNV-1a over **unsigned** bytes. The default partitioner's hash since 0.3.0."""
    h = FNV_OFFSET_BASIS
    for b in data:  # iterating `bytes` yields unsigned ints, which is the specified reading
        h = ((h ^ b) * FNV_PRIME) & MASK32
    return h


def java_array_hash(data: bytes) -> int:
    """`java.util.Arrays.hashCode(byte[])`, written out rather than delegated — the pre-0.3.0 default.

    The subtlety is one line: Java's array element is a **signed** byte, so 0x80 enters the sum as
    -128 and not as 128. A language whose bytes are unsigned diverges on every key with a high byte
    while passing every ASCII test.
    """
    h = 1
    for b in data:
        h = (31 * h + (b - 256 if b > 127 else b)) & MASK32
    return h


def crc32c(data: bytes) -> int:
    """CRC-32C (Castagnoli). Not `zlib.crc32` — a different polynomial, silently."""
    c = MASK32
    for b in data:
        c = CRC32C_TABLE[(c ^ b) & 0xFF] ^ (c >> 8)
    return c ^ MASK32


def to_signed32(h: int) -> int:
    return h - (1 << 32) if h >= (1 << 31) else h


def partition_fnv1a(key: bytes, partitions: int) -> int:
    """Unsigned remainder, so the fold never has to say how a language signs its integers."""
    return fnv1a32(key) % partitions


def partition_java_array_hash(key: bytes, partitions: int) -> int:
    """`Math.floorMod`, which for a positive divisor is exactly Python's `%` on the signed hash."""
    return to_signed32(java_array_hash(key)) % partitions


assert crc32c(b"123456789") == 0xE3069283, "CRC-32C check value mismatch — the table is wrong"
