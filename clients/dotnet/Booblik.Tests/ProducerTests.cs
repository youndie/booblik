using System.Text;

namespace Booblik.Tests;

public sealed class ProducerTests
{
    /// <summary>
    /// The regression test the JVM client needed twice, ported before it was needed here.
    /// </summary>
    /// <remarks>
    /// Records arrive one per window, so each is delivered by the timer rather than by a full batch
    /// — the interleaving where the JVM accumulator lost a record, its timeout having cancelled the
    /// pending receive and the cancelled receive having swallowed it. <c>WaitToReadAsync</c> plus
    /// <c>TryRead</c> cannot do that: waiting takes nothing, and reading is synchronous.
    /// </remarks>
    [Fact]
    public async Task NoRecordsAreLostAcrossLingerWindows()
    {
        var linger = TimeSpan.FromMilliseconds(1);
        const int rounds = 300;

        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);
        await using var producer = new Producer(
            connection,
            new ProducerConfig { Linger = linger, MaxBatchSize = 100 });

        var pending = new List<Task<long>>(rounds);
        for (var index = 0; index < rounds; index++)
        {
            pending.Add(producer.SendAsync("orders", 0, Encoding.UTF8.GetBytes($"r-{index}")));
            // Sleeping the window, not less: without this the records pile into full batches and
            // the interleaving this test is named after never happens.
            await Task.Delay(linger);
        }

        var offsets = await Task.WhenAll(pending);
        for (var index = 0; index < rounds; index++)
        {
            Assert.Equal(index, offsets[index]);
        }

        Assert.Equal(rounds, broker.RecordsIn("orders", 0).Count);

        // And the interleaving actually happened. Without this the assertions above pass just as
        // well when the records pile into three full batches, which is the opposite of what this
        // test is named after — it would be green while checking nothing.
        Assert.True(
            broker.Requests > rounds / 10,
            $"{rounds} records went out in {broker.Requests} requests, so the window never fired");
    }

    /// <summary>An hour of linger is still pending; only the batch being full can complete this.</summary>
    [Fact]
    public async Task AFullBatchDoesNotWaitForTheWindow()
    {
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);
        await using var producer = new Producer(
            connection,
            new ProducerConfig { Linger = TimeSpan.FromHours(1), MaxBatchSize = 10 });

        var pending = Enumerable.Range(0, 10)
            .Select(index => producer.SendAsync("orders", 0, Encoding.UTF8.GetBytes($"r-{index}")))
            .ToList();

        Assert.Equal(9, await pending[^1].WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.Equal(1, broker.Requests);
    }

    /// <summary>A request addresses one partition — a partition being what has one writer.</summary>
    [Fact]
    public async Task PartitionsAccumulateSeparately()
    {
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);
        await using var producer = new Producer(
            connection,
            new ProducerConfig { Linger = TimeSpan.FromHours(1), MaxBatchSize = 100 });

        var pending = Enumerable.Range(0, 3)
            .Select(partition => producer.SendAsync("orders", partition, "x"u8.ToArray()))
            .ToList();

        await producer.FlushAsync().WaitAsync(TimeSpan.FromSeconds(10));
        await Task.WhenAll(pending);

        for (var partition = 0; partition < 3; partition++)
        {
            Assert.Single(broker.RecordsIn("orders", partition));
        }

        Assert.Equal(3, broker.Requests);
    }

    /// <summary>Dropping queued records would make every clean shutdown a silent data loss.</summary>
    [Fact]
    public async Task DisposeFlushesWhatIsQueued()
    {
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);
        var producer = new Producer(
            connection,
            new ProducerConfig { Linger = TimeSpan.FromHours(1), MaxBatchSize = 100 });

        var pending = producer.SendAsync("orders", 0, "x"u8.ToArray());
        await producer.DisposeAsync();

        Assert.Equal(0, await pending);
        Assert.Single(broker.RecordsIn("orders", 0));
        // A statement lambda, so this picks the synchronous overload: SendAsync returns a Task but
        // refuses a closed producer **before** any of it runs, and xunit's analyser cannot tell.
        Assert.Throws<ObjectDisposedException>(() =>
        {
            _ = producer.SendAsync("orders", 0, "y"u8.ToArray());
        });
    }

    /// <summary>
    /// Nothing is ever going to complete this from the wire, so it has to say so rather than hanging.
    /// </summary>
    [Fact]
    public async Task AckNoneCompletesWithAnUnknownOffset()
    {
        using var broker = new FakeBroker();
        using var connection = await Connection.ConnectAsync(broker.Address);
        await using var producer = new Producer(
            connection,
            new ProducerConfig { Linger = TimeSpan.FromMilliseconds(1), Ack = AckPolicy.None });

        var offset = await producer.SendAsync("orders", 0, "x"u8.ToArray())
            .WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(Producer.OffsetUnknown, offset);
    }

    /// <summary>
    /// A batch that fails fails for all of its records. Completing some and abandoning the rest
    /// would leave callers awaiting a task nothing will ever finish.
    /// </summary>
    [Fact]
    public async Task ABrokerFailureReachesEveryWaitingCaller()
    {
        using var broker = new FakeBroker { RefuseWith = Code.RecordTooLarge };
        using var connection = await Connection.ConnectAsync(broker.Address);
        await using var producer = new Producer(
            connection,
            new ProducerConfig { Linger = TimeSpan.FromMilliseconds(1), MaxBatchSize = 100 });

        var pending = Enumerable.Range(0, 5)
            .Select(_ => producer.SendAsync("orders", 0, "x"u8.ToArray()))
            .ToList();

        foreach (var task in pending)
        {
            await Assert.ThrowsAsync<BrokerException>(() => task.WaitAsync(TimeSpan.FromSeconds(10)));
        }
    }
}
