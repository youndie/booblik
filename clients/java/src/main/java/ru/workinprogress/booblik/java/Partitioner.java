package ru.workinprogress.booblik.java;

/**
 * The partitioner, specified in {@code docs/api/protocol-wire.md} §7 and pinned by the golden
 * vectors in {@code conformance/vectors/partitioner-fnv1a.tsv}.
 *
 * <p>It matters more than its size suggests. The broker never sees the key — the record format has
 * no room for one — so the client picks the partition and sends the number. Two publishers that hash
 * a key differently put it in two partitions, and per-key order, which is what partitions are for,
 * is gone. Nothing errors when that happens: the record lands in a partition that exists, just not
 * the same one.
 */
public final class Partitioner {

    public static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    public static final int FNV_PRIME = 0x01000193;

    private Partitioner() {}

    /**
     * The 32-bit FNV-1a hash of {@code key}, returned in an {@code int} holding 32 unsigned bits.
     *
     * <p><b>{@code & 0xFF} is the whole point.</b> Java's {@code byte} is signed, so without the
     * mask 0x80 would sign-extend to 0xFFFFFF80 before the XOR and this would disagree with every
     * language whose bytes are unsigned. It is the same line Kotlin needs a mask on, Python needs a
     * mask on for the opposite reason, and JavaScript needs {@code Math.imul} for — and the vectors
     * carry 0x80 to catch all of them.
     *
     * <p>Overflow wraps, which is what FNV-1a is defined in; Java has no checked arithmetic to opt
     * out of, so nothing has to be said to the compiler here.
     */
    public static int fnv1a32(byte[] key) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : key) {
            hash = (hash ^ (b & 0xFF)) * FNV_PRIME;
        }
        return hash;
    }

    /**
     * Folds the hash of {@code key} into {@code [0, partitions)}.
     *
     * <p>Unsigned remainder, which is why the specification never has to say how a language signs
     * its integers. Throws rather than returning something for a non-positive count: there is no
     * partition to pick, and returning 0 would send records to one that may not exist.
     */
    public static int partitionFor(byte[] key, int partitions) {
        if (partitions <= 0) {
            throw new IllegalArgumentException("partitionFor needs at least one partition");
        }
        return (int) (Integer.toUnsignedLong(fnv1a32(key)) % partitions);
    }
}
