namespace Booblik.Tests;

public sealed class ConnectionTests
{
    private static byte[] AllBytes()
    {
        var bytes = new byte[256];
        for (var index = 0; index < bytes.Length; index++)
        {
            bytes[index] = (byte)index;
        }

        return bytes;
    }

    [Fact]
    public async Task ARecordArrivesByteForByte()
    {
        // Every byte value, because an encoding that damages the payload usually damages the high
        // half of it and leaves ASCII intact.
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);

        List<byte[]> records = [AllBytes(), "second"u8.ToArray(), [0x00]];
        var result = await connection.ProduceAsync("orders", 2, records);

        Assert.NotNull(result);
        Assert.Equal(0, result.Value.BaseOffset);
        Assert.Equal(3, result.Value.LogEndOffset);
        Assert.Equal(records, broker.RecordsIn("orders", 2));
    }

    /// <summary>
    /// The broker sends nothing at all, and a client that reads a response here blocks for ever. The
    /// bound is what makes this a test rather than a hang.
    /// </summary>
    [Fact]
    public async Task AckNoneDoesNotWaitForAnAnswer()
    {
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);

        var produce = connection.ProduceAsync("orders", 0, ["x"u8.ToArray()], AckPolicy.None);
        var finished = await Task.WhenAny(produce, Task.Delay(TimeSpan.FromSeconds(3)));

        Assert.Same(produce, finished);
        Assert.Null(await produce);
    }

    [Fact]
    public async Task ARefusalIsAnErrorAndKeepsTheConnection()
    {
        using var broker = new FakeBroker { RefuseWith = Code.UnknownTopicOrPartition };
        using var connection = await Connection.ConnectAsync(broker.Address);

        var refusal = await Assert.ThrowsAsync<BrokerException>(
            () => connection.ProduceAsync("nope", 0, ["x"u8.ToArray()]));
        Assert.Equal(Code.UnknownTopicOrPartition, refusal.Code);

        // Framing was intact, so the connection is still usable — only a frame length out of range
        // closes one. A client that tore the socket down here would turn a refusal into an outage.
        broker.RefuseWith = Code.None;
        Assert.NotNull(await connection.ProduceAsync("orders", 0, ["x"u8.ToArray()]));
    }

    [Fact]
    public async Task MetadataAndKeyRouting()
    {
        using var broker = new FakeBroker(3);
        using var connection = await Connection.ConnectAsync(broker.Address);

        var topic = await connection.TopicAsync("orders");
        Assert.Equal(3, topic.Partitions.Count);

        var key = "user-1"u8.ToArray();
        Assert.Equal(topic.Partitions[Partitioner.PartitionFor(key, 3)], topic.PartitionFor(key));
        // The same key, every time. This is what makes asking-then-sending safe with a key.
        Assert.Equal(topic.PartitionFor(key), topic.PartitionFor(key));
    }

    [Fact]
    public async Task UnkeyedRoutingAdvancesRoundRobin()
    {
        using var broker = new FakeBroker(3);
        using var connection = await Connection.ConnectAsync(broker.Address);

        var topic = await connection.TopicAsync("orders");
        var seen = Enumerable.Range(0, 9).Select(_ => topic.PartitionFor(null)).ToList();

        var counts = seen.GroupBy(partition => partition).ToDictionary(g => g.Key, g => g.Count());
        Assert.Equal(3, counts.Count);
        Assert.All(counts.Values, count => Assert.Equal(3, count));
    }

    /// <summary>
    /// A response cut short by a broker restart is a connection problem, not an exception the caller
    /// has never heard of coming out of a decode.
    /// </summary>
    [Fact]
    public void ATruncatedResponseIsAProtocolError()
    {
        Assert.Throws<ProtocolException>(() => Connection.DecodeMetadata([0, 0, 0, 1, 0, 5]));
    }
}
