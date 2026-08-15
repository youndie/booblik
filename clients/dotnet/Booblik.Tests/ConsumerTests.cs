namespace Booblik.Tests;

public sealed class ConsumerTests
{
    /// <summary>
    /// The golden vectors, found by walking up rather than trusting the working directory: the test
    /// runner starts in bin/Debug/net8.0 and an editor may not, and a fixture that resolves in one
    /// but not the other gets deleted by whoever hits it second.
    /// </summary>
    private static List<string[]> ReadVectors(string name)
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "conformance", "vectors", name)))
        {
            directory = directory.Parent;
        }

        Assert.NotNull(directory);
        return File.ReadAllLines(Path.Combine(directory!.FullName, "conformance", "vectors", name))
            .Where(line => line.Length > 0 && !line.StartsWith('#'))
            // Split keeps empty fields, so the empty-payload vector arrives as a leading "" — which
            // is the vector a hand-written parser drops first.
            .Select(line => line.Split('\t'))
            .ToList();
    }

    private static byte[] FromHex(string hex) => Convert.FromHexString(hex);

    private static byte[] Repeat(byte value, int count) => Enumerable.Repeat(value, count).ToArray();

    /// <summary>
    /// Holds this implementation to vectors computed by another one, in another language. "CRC32"
    /// names at least three different functions, all of which return a plausible number, so agreeing
    /// with an independent reading of the specification is the only property worth asserting.
    /// If this fails, this code is wrong — not the vectors.
    /// </summary>
    [Fact]
    public void ChecksumMatchesGoldenVectors()
    {
        var rows = ReadVectors("crc32c.tsv");
        Assert.NotEmpty(rows);

        foreach (var row in rows)
        {
            Assert.Equal(uint.Parse(row[1]), Crc32C.Compute(FromHex(row[0])));
        }
    }

    /// <summary>
    /// The one number that separates CRC-32C from the CRC32 a hand reaches for first.
    /// System.IO.Hashing.Crc32 gives 0xCBF43926 for the same input.
    /// </summary>
    [Fact]
    public void ChecksumIsCastagnoliAndNotTheOtherOne()
    {
        Assert.Equal(0xE3069283u, Crc32C.Compute("123456789"u8));
    }

    [Fact]
    public async Task RecordsComeBackInOrder()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);

        byte[][] payloads = [Repeat(0xAB, 300), [0x00], "third"u8.ToArray()];
        broker.Seed("t", 0, payloads);

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        var records = await consumer.PollAsync();

        Assert.Equal(payloads, records);
        Assert.Equal(3, consumer.Position);
        Assert.Equal(3, consumer.HighWatermark);
        Assert.Equal(0, consumer.Lag);
    }

    /// <summary>
    /// The steady state of a consumer that is keeping up, and the one it must not read as the end of
    /// the log.
    /// </summary>
    [Fact]
    public async Task FetchingAtTheHighWatermarkIsEmptyAndNotAnError()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, "one"u8.ToArray());

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        await consumer.PollAsync();

        Assert.Empty(await consumer.PollAsync());
        Assert.Equal(1, consumer.Position);
    }

    /// <summary>
    /// maxBytes cuts on a byte boundary, so a full response normally ends inside a record. Returning
    /// the fragment corrupts data; counting it as the end of the log stalls for ever.
    /// </summary>
    [Fact]
    public async Task TruncatedTailIsDroppedAndRefetched()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, Repeat((byte)'A', 100), Repeat((byte)'B', 100));

        // One whole record is 8 bytes of header and 100 of payload; 150 stops inside the second.
        var consumer = Waiting(connection.CreateConsumer("t", 0));
        consumer.MaxBytes = 150;

        Assert.Equal([Repeat((byte)'A', 100)], await consumer.PollAsync());
        Assert.Equal(1, consumer.Position);
        Assert.Equal([Repeat((byte)'B', 100)], await consumer.PollAsync());
    }

    /// <summary>The other branch: no size field to read, so it is found by having bytes left over.</summary>
    [Fact]
    public async Task ResponseStoppingInsideARecordHeaderIsTruncation()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, Repeat((byte)'A', 20), Repeat((byte)'B', 20));

        // 28 bytes is the first record whole, then 4 bytes into the second record's 8-byte header.
        var consumer = Waiting(connection.CreateConsumer("t", 0));
        consumer.MaxBytes = 32;

        Assert.Single(await consumer.PollAsync());
        Assert.Equal(1, consumer.Position);
    }

    /// <summary>
    /// The stall that does not resolve itself: every retry makes the identical request, so the
    /// consumer keeps running, reports nothing and never advances.
    /// </summary>
    [Fact]
    public async Task RecordLargerThanMaxBytesIsReported()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, Repeat((byte)'A', 500));

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        consumer.MaxBytes = 100;

        var failure = await Assert.ThrowsAsync<RecordExceedsMaxBytesException>(() => consumer.PollAsync());
        Assert.Equal(500, failure.RecordBytes);
        Assert.Equal(100, failure.MaxBytes);
        Assert.Equal(0, failure.Offset);
        Assert.Equal(0, consumer.Position);
    }

    /// <summary>
    /// The client is the only party that can catch this: on the zero-copy path the broker never
    /// touches the record bytes it sends.
    /// </summary>
    [Fact]
    public async Task CorruptRecordIsRejected()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, "payload"u8.ToArray());
        broker.Corrupt = true;

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        var failure = await Assert.ThrowsAsync<CorruptRecordException>(() => consumer.PollAsync());

        Assert.Equal(0, failure.Offset);
        Assert.NotEqual(failure.Stored, failure.Computed);
    }

    /// <summary>
    /// One line apart in the decoder: the length check has to come before the checksum, or every
    /// full response is an alarm.
    /// </summary>
    [Fact]
    public async Task TruncationIsNotReportedAsCorruption()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, Repeat((byte)'A', 100), Repeat((byte)'B', 100));

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        consumer.MaxBytes = 150;

        Assert.Single(await consumer.PollAsync());
    }

    /// <summary>
    /// A record whose checksum has the high bit set, end to end.
    /// </summary>
    /// <remarks>
    /// In Python and JavaScript this is the vector that catches a signed read of the stored sum. In
    /// C# it catches nothing — a cast between int and uint preserves the bits, so both readings
    /// compare equal, which was checked by mutation. Kept anyway, because it is the same vector the
    /// other four clients carry and a reader comparing them should find it here too.
    /// </remarks>
    [Fact]
    public async Task ARecordWhoseChecksumHasTheHighBitSetRoundTrips()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);

        var payload = Enumerable.Range(0, 256)
            .Select(value => new[] { (byte)value })
            .First(candidate => Crc32C.Compute(candidate) > int.MaxValue);
        broker.Seed("t", 0, payload);

        Assert.Equal([payload], await Waiting(connection.CreateConsumer("t", 0)).PollAsync());
    }

    /// <summary>
    /// Always v2, so the waiting fields are not exercised only in the branch nobody debugs. Asserted
    /// from the broker's side, the only place that can tell what was actually sent.
    /// </summary>
    [Fact]
    public async Task FetchGoesOutAsV2WithTheWaitingFields()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, "one"u8.ToArray());

        await connection.FetchAsync("t", 0, 0, maxBytes: 4096, maxWaitMillis: 250, minBytes: 64);

        Assert.Equal(2, broker.LastVersion);
        Assert.Equal(("t", 0, 0L, 4096, 250, 64), broker.LastFetch);
    }

    [Fact]
    public async Task RefusalReachesTheCaller()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.RefuseWith = Code.OffsetOutOfRange;

        var failure = await Assert.ThrowsAsync<BrokerException>(
            () => Waiting(connection.CreateConsumer("t", 0)).PollAsync());
        Assert.Equal(Code.OffsetOutOfRange, failure.Code);
    }

    [Fact]
    public async Task SeekMovesThePosition()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, "zero"u8.ToArray(), "one"u8.ToArray(), "two"u8.ToArray());

        var consumer = Waiting(connection.CreateConsumer("t", 0));
        consumer.Seek(2);

        Assert.Equal(["two"u8.ToArray()], await consumer.PollAsync());
    }

    [Fact]
    public async Task AwaitForeachYieldsEveryRecordOnce()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        byte[][] seeded = ["a"u8.ToArray(), "b"u8.ToArray(), "c"u8.ToArray()];
        broker.Seed("t", 0, seeded);

        var read = new List<byte[]>();
        await foreach (var record in Waiting(connection.CreateConsumer("t", 0)).RecordsAsync())
        {
            read.Add(record);
            if (read.Count == seeded.Length)
            {
                break;
            }
        }

        Assert.Equal(seeded, read);
    }

    /// <summary>
    /// The endless stream is stopped by cancelling its token, and the cancellation comes out of the
    /// await inside rather than being swallowed. The bound is what makes this a test, not a hang.
    /// </summary>
    [Fact]
    public async Task CancellationStopsTheStream()
    {
        using var broker = new FakeBroker(1);
        using var connection = await Connection.ConnectAsync(broker.Address);
        broker.Seed("t", 0, "only"u8.ToArray());

        using var stopping = new CancellationTokenSource();
        var consumer = Waiting(connection.CreateConsumer("t", 0));

        var reading = Task.Run(async () =>
        {
            await foreach (var _ in consumer.RecordsAsync(stopping.Token))
            {
                stopping.Cancel();
            }
        });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => reading.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    /// <summary>The fixture answers immediately, so waiting would only be time spent.</summary>
    private static Consumer Waiting(Consumer consumer)
    {
        consumer.MaxWaitMillis = 0;
        return consumer;
    }
}
