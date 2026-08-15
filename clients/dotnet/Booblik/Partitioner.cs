namespace Booblik;

/// <summary>
/// The partitioner, specified in docs/api/protocol-wire.md §7 and pinned by the golden vectors in
/// conformance/vectors/partitioner-fnv1a.tsv.
/// </summary>
/// <remarks>
/// It matters more than its size suggests. The broker never sees the key — the record format has no
/// room for one — so the client picks the partition and sends the number. Two publishers that hash a
/// key differently put it in two partitions, and per-key order, which is what partitions are for, is
/// gone. Nothing errors when that happens: the record lands in a partition that exists, just not the
/// same one.
/// </remarks>
public static class Partitioner
{
    public const uint FnvOffsetBasis = 0x811C9DC5;
    public const uint FnvPrime = 0x01000193;

    /// <summary>The 32-bit FNV-1a hash of <paramref name="key"/>, over its bytes as unsigned values.</summary>
    /// <remarks>
    /// <para>
    /// <c>unchecked</c> is not decoration. FNV-1a is defined in arithmetic that wraps at 32 bits, and
    /// a project built with <c>CheckForOverflowUnderflow</c> would otherwise throw here on the second
    /// byte of almost any key. Saying it in the code makes the algorithm independent of a build
    /// setting somebody else chooses.
    /// </para>
    /// <para>
    /// C#'s <c>byte</c> is unsigned, so the XOR needs nothing extra — unlike <c>sbyte</c>, and unlike
    /// Java and Kotlin, whose array elements are signed and need a mask at this line. The vectors
    /// carry 0x80 for exactly that reason.
    /// </para>
    /// </remarks>
    public static uint Fnv1a32(ReadOnlySpan<byte> key)
    {
        unchecked
        {
            var hash = FnvOffsetBasis;
            foreach (var b in key)
            {
                hash = (hash ^ b) * FnvPrime;
            }

            return hash;
        }
    }

    /// <summary>Folds the hash of <paramref name="key"/> into <c>[0, partitions)</c>.</summary>
    /// <remarks>
    /// Unsigned remainder, which is why the specification never has to say how a language signs its
    /// integers. Throws rather than returning something for a non-positive count: there is no
    /// partition to pick, and returning 0 would send records to one that may not exist.
    /// </remarks>
    public static int PartitionFor(ReadOnlySpan<byte> key, int partitions)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(partitions);
        return (int)(Fnv1a32(key) % (uint)partitions);
    }
}
