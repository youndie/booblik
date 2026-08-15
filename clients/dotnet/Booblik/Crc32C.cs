namespace Booblik;

/// <summary>CRC-32C (Castagnoli), specified in docs/api/protocol-wire.md §7.2.</summary>
/// <remarks>
/// <para>
/// <b>Not <c>System.IO.Hashing.Crc32</c></b>, which is CRC-32 with polynomial <c>0x04C11DB7</c>;
/// this is <c>0x1EDC6F41</c>. Both are called "CRC32", both take bytes and return a 32-bit number,
/// and substituting one for the other looks like nothing at all — the client simply rejects every
/// record it reads. The check value for <c>123456789</c> is <c>0xE3069283</c>;
/// <c>System.IO.Hashing.Crc32</c> gives <c>0xCBF43926</c>.
/// </para>
/// <para>
/// .NET has no CRC-32C in the box — <c>System.IO.Hashing</c> is a NuGet package, and the wrong
/// algorithm at that. This library has <b>no dependencies</b>, and a checksum is not where that rule
/// gets its first exception: the point of verifying is that it happens everywhere, and an optional
/// dependency makes verification optional. So here are the forty lines.
/// </para>
/// <para>
/// The table is built from the <b>reflected</b> polynomial <c>0x82F63B78</c>, because this shifts
/// right. Using <c>0x1EDC6F41</c> with a right shift produces a stable, plausible, everywhere-wrong
/// sum. Everything is <c>uint</c>: with signed arithmetic the right shift would carry the sign bit
/// down and half of all sums would come out wrong — <c>&gt;&gt;&gt;</c> exists in C# only since 11,
/// and using the right type says it once instead of at every shift.
/// </para>
/// <para>Pinned by conformance/vectors/crc32c.tsv, computed by an independent implementation.</para>
/// </remarks>
public static class Crc32C
{
    /// <summary>The reflected form of 0x1EDC6F41. Mixing the two up is the silent way to get this wrong.</summary>
    private const uint Polynomial = 0x82F63B78;

    // Built once: 256 entries, and rebuilding it per record would be most of the cost of checking a
    // small one.
    private static readonly uint[] Table = BuildTable();

    /// <summary>The CRC-32C of <paramref name="data"/>.</summary>
    /// <remarks><c>init</c> and <c>xorout</c> are both 0xFFFFFFFF, which is where the two XORs come from.</remarks>
    public static uint Compute(ReadOnlySpan<byte> data)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var value in data)
        {
            crc = Table[(crc ^ value) & 0xFF] ^ (crc >> 8);
        }

        return crc ^ 0xFFFFFFFFu;
    }

    private static uint[] BuildTable()
    {
        var table = new uint[256];
        for (var index = 0u; index < 256; index++)
        {
            var value = index;
            for (var bit = 0; bit < 8; bit++)
            {
                value = (value & 1) != 0 ? (value >> 1) ^ Polynomial : value >> 1;
            }

            table[index] = value;
        }

        return table;
    }
}
