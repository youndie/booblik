using System.Diagnostics;
using System.Threading.Channels;

namespace Booblik;

/// <summary>Tunes the accumulator.</summary>
public sealed record ProducerConfig
{
    /// <summary>Records per request. Reached first, the batch goes at once.</summary>
    public int MaxBatchSize { get; init; } = 100;

    /// <summary>How long an incomplete batch waits for company.</summary>
    /// <remarks>
    /// Zero is not the fast setting. It sends every record on its own, which the broker's own
    /// measurements put at 80 592 records/s against 4 335 482 for batches of a hundred — the
    /// accumulator is the single largest performance factor in this project, an order of magnitude
    /// more than the choice of write path. Non-zero trades a bounded amount of latency for it.
    /// </remarks>
    public TimeSpan Linger { get; init; } = TimeSpan.FromMilliseconds(5);

    public AckPolicy Ack { get; init; } = AckPolicy.Written;

    /// <summary>
    /// Bounds one delivery, so a broker that stops answering fails the records waiting on it instead
    /// of hanging the accumulator for ever.
    /// </summary>
    public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(30);
}

/// <summary>Accumulates records and sends them in batches.</summary>
/// <remarks>
/// <para>
/// <b>It owns its Connection</b>: one loop holds the pending records and is the only writer to that
/// socket. Do not use the same Connection directly while a Producer has it — responses are matched
/// in order, and a second writer takes somebody else's answer.
/// </para>
/// <para>
/// Records for different partitions accumulate separately and go out as separate requests, because
/// a request addresses one partition — a partition being what has one writer.
/// </para>
/// </remarks>
public sealed class Producer : IAsyncDisposable
{
    /// <summary>
    /// What a send completes with when the batch went out under <see cref="AckPolicy.None"/>. The
    /// record was sent; no offset exists, because none is assigned until the writer reaches the batch.
    /// </summary>
    public const long OffsetUnknown = -1;

    private readonly Connection _connection;
    private readonly ProducerConfig _config;
    private readonly Channel<Command> _mailbox = Channel.CreateUnbounded<Command>();
    private readonly Task _loop;
    private int _closed;

    public Producer(Connection connection, ProducerConfig? config = null)
    {
        _connection = connection;
        _config = config ?? new ProducerConfig();
        _loop = Task.Run(RunAsync);
    }

    /// <summary>Queues a record and returns where its offset will arrive.</summary>
    /// <remarks>
    /// The record is not on the wire when this returns — that is the point. Await the task to know
    /// it landed, or call <see cref="FlushAsync"/> to push everything queued.
    /// </remarks>
    public Task<long> SendAsync(string topic, int partition, byte[] record)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _closed) != 0, this);

        var completion = new TaskCompletionSource<long>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_mailbox.Writer.TryWrite(new Command(topic, partition, record, completion, null)))
        {
            throw new InvalidOperationException("booblik: producer is closed");
        }

        return completion.Task;
    }

    /// <summary>Sends everything queued and waits for the broker to answer all of it.</summary>
    public async Task FlushAsync()
    {
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_mailbox.Writer.TryWrite(new Command(string.Empty, 0, [], null, done)))
        {
            throw new InvalidOperationException("booblik: producer is closed");
        }

        await done.Task.ConfigureAwait(false);
    }

    /// <summary>Flushes what is queued and stops the accumulator. Does not close the Connection.</summary>
    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0)
        {
            return;
        }

        _mailbox.Writer.Complete();
        await _loop.ConfigureAwait(false);
    }

    private async Task RunAsync()
    {
        var pending = new Dictionary<(string Topic, int Partition), Batch>();
        var window = new Stopwatch();
        var reader = _mailbox.Reader;

        while (true)
        {
            using var timer = pending.Count > 0 ? new CancellationTokenSource(Remaining(window)) : null;

            bool available;
            try
            {
                // `WaitToReadAsync` and then `TryRead`, never `ReadAsync(token)`. Waiting only
                // signals that something is there and takes nothing, and `TryRead` is synchronous —
                // so a cancelled window cannot take a record off the channel and then drop it. That
                // is exactly what the equivalent on the JVM did twice, after which whoever awaited
                // that record's offset waited for ever while the accumulator served everybody else.
                available = await reader.WaitToReadAsync(timer?.Token ?? CancellationToken.None)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                await DeliverAndResetAsync().ConfigureAwait(false);
                continue;
            }

            if (!available)
            {
                await DeliverAndResetAsync().ConfigureAwait(false);
                return;
            }

            while (reader.TryRead(out var command))
            {
                if (command.Flush is { } flush)
                {
                    await DeliverAndResetAsync().ConfigureAwait(false);
                    flush.TrySetResult();
                    continue;
                }

                var key = (command.Topic, command.Partition);
                if (!pending.TryGetValue(key, out var batch))
                {
                    batch = new Batch();
                    pending[key] = batch;

                    // The window is measured from the **first** record of the batch and never
                    // restarted. Timing from the last would let a steady trickle postpone the send
                    // indefinitely, turning a latency bound into a latency hope.
                    if (!window.IsRunning)
                    {
                        window.Restart();
                    }
                }

                batch.Records.Add(command.Record);
                batch.Waiting.Add(command.Completion!);

                if (batch.Records.Count >= _config.MaxBatchSize)
                {
                    await DeliverAndResetAsync().ConfigureAwait(false);
                }
            }
        }

        // Delivering always stops the window. Without this the stopwatch keeps running past the
        // send, and the next batch is born with its window already spent — a linger setting that
        // works once and then behaves as zero for the rest of the process.
        async Task DeliverAndResetAsync()
        {
            await DeliverAsync(pending).ConfigureAwait(false);
            window.Reset();
        }

        TimeSpan Remaining(Stopwatch running)
        {
            var left = _config.Linger - running.Elapsed;
            return left > TimeSpan.Zero ? left : TimeSpan.Zero;
        }
    }

    private async Task DeliverAsync(Dictionary<(string Topic, int Partition), Batch> pending)
    {
        if (pending.Count == 0)
        {
            return;
        }

        foreach (var (key, batch) in pending.ToArray())
        {
            pending.Remove(key);

            using var timeout = new CancellationTokenSource(_config.RequestTimeout);
            try
            {
                var result = await _connection
                    .ProduceAsync(key.Topic, key.Partition, batch.Records, _config.Ack, timeout.Token)
                    .ConfigureAwait(false);

                for (var index = 0; index < batch.Waiting.Count; index++)
                {
                    // One request is written by one call, so the records are contiguous.
                    batch.Waiting[index].TrySetResult(
                        result is { } offsets ? offsets.BaseOffset + index : OffsetUnknown);
                }
            }
            catch (Exception failure)
            {
                // A batch that fails fails for all of its records. Completing some and abandoning
                // the rest would leave callers awaiting a task nothing will ever finish.
                foreach (var waiting in batch.Waiting)
                {
                    waiting.TrySetException(failure);
                }
            }
        }
    }

    private sealed record Command(
        string Topic,
        int Partition,
        byte[] Record,
        TaskCompletionSource<long>? Completion,
        TaskCompletionSource? Flush);

    private sealed class Batch
    {
        public List<byte[]> Records { get; } = [];

        public List<TaskCompletionSource<long>> Waiting { get; } = [];
    }
}
