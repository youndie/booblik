using System.Globalization;

namespace Booblik.Tests;

/// <summary>
/// Holds this client to the golden vectors, computed by another implementation in another language.
/// </summary>
/// <remarks>
/// Agreeing with itself is what a wrong partitioner also does; agreeing with an independent reading
/// of the written specification is the property that matters. <b>If this fails, this code is wrong —
/// not the vectors.</b>
/// </remarks>
public sealed class PartitionerTests
{
    // The fold columns, in the order the vector file's header gives them.
    private static readonly int[] PartitionCounts = [1, 2, 3, 4, 16, 64];

    [Fact]
    public void MatchesTheGoldenVectors()
    {
        var rows = ReadVectors("partitioner-fnv1a.tsv");
        Assert.NotEmpty(rows);

        foreach (var row in rows)
        {
            var name = row[^1];
            var key = Convert.FromHexString(row[0]);

            Assert.Equal(uint.Parse(row[1], CultureInfo.InvariantCulture), Partitioner.Fnv1a32(key));

            for (var index = 0; index < PartitionCounts.Length; index++)
            {
                var partitions = PartitionCounts[index];
                Assert.Equal(
                    int.Parse(row[2 + index], CultureInfo.InvariantCulture),
                    Partitioner.PartitionFor(key, partitions));
            }
        }
    }

    /// <summary>
    /// C#'s <c>byte</c> is unsigned, so 0x80 cannot become −128 by accident here — unlike Java and
    /// Kotlin, whose array elements are signed. Asserted rather than assumed, because this is the
    /// line every port gets wrong and an <c>sbyte</c> is one keystroke away.
    /// </summary>
    [Fact]
    public void HighBytesAreUnsigned()
    {
        var signExtended = unchecked((Partitioner.FnvOffsetBasis ^ 0xFFFFFF80) * Partitioner.FnvPrime);
        Assert.NotEqual(signExtended, Partitioner.Fnv1a32([0x80]));
    }

    [Fact]
    public void NoPartitionsIsRefused()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => Partitioner.PartitionFor("k"u8, 0));
    }

    private static List<string[]> ReadVectors(string name)
    {
        var path = FindUpwards(Path.Combine("conformance", "vectors", name));
        var rows = new List<string[]>();

        foreach (var line in File.ReadAllLines(path))
        {
            if (line.Length == 0 || line.StartsWith('#'))
            {
                continue;
            }

            // Split keeps empty fields, so the empty-key vector arrives as a leading "" — which is
            // the vector a hand-written parser drops first.
            rows.Add(line.Split('\t'));
        }

        return rows;
    }

    /// <summary>
    /// Walks up rather than trusting the working directory: the runner starts in the build output
    /// and an editor may not, and a fixture that resolves in one but not the other gets deleted by
    /// whoever hits it second.
    /// </summary>
    private static string FindUpwards(string relative)
    {
        var directory = AppContext.BaseDirectory;
        while (true)
        {
            var candidate = Path.Combine(directory, relative);
            if (File.Exists(candidate))
            {
                return candidate;
            }

            var parent = Directory.GetParent(directory);
            if (parent is null)
            {
                throw new FileNotFoundException($"{relative} not found above {AppContext.BaseDirectory}");
            }

            directory = parent.FullName;
        }
    }
}
