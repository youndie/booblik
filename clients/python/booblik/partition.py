"""The partitioner, specified in docs/api/protocol-wire.md §7.

It matters more than its size suggests. The broker never sees the key — the record format has no
room for one — so the client picks the partition and sends the number. Two publishers that hash a
key differently put it in two partitions, and per-key order, which is what partitions are for, is
gone. Nothing errors when that happens: the record lands in a partition that exists, just not the
same one.

Pinned by the golden vectors in conformance/vectors/partitioner-fnv1a.tsv, computed by another
implementation in another language.
"""

FNV_OFFSET_BASIS = 0x811C9DC5
FNV_PRIME = 0x01000193

MASK32 = 0xFFFFFFFF


def fnv1a32(key: bytes) -> int:
    """The 32-bit FNV-1a hash of ``key``, over its bytes as unsigned values.

    **The mask is not tidiness — it is the algorithm.** Python integers do not overflow, so without
    it this would compute an ever-growing number that agrees with nothing: every other language
    wraps at 32 bits and that wrapping is what FNV-1a is defined in. This is Python's version of the
    trap that costs Java and Kotlin a cast at the XOR.

    Iterating ``bytes`` yields unsigned ints, so the XOR needs nothing extra here.
    """
    hash_value = FNV_OFFSET_BASIS
    for byte in key:
        hash_value = ((hash_value ^ byte) * FNV_PRIME) & MASK32
    return hash_value


def partition_for(key: bytes, partitions: int) -> int:
    """Folds the hash of ``key`` into ``range(partitions)``.

    Unsigned remainder, which is why the specification never has to say how a language signs its
    integers. Raises rather than returning something for a non-positive count: there is no partition
    to pick, and returning 0 would send records to one that may not exist.
    """
    if partitions <= 0:
        raise ValueError("partition_for needs at least one partition")
    return fnv1a32(key) % partitions
