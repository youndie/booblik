using System.Buffers.Binary;
using System.Runtime.CompilerServices;

namespace Booblik;

/// <summary>One FETCH response, unframed and checksum-verified.</summary>
/// <param name="HighWatermark">The first offset that does not exist yet, as of this response.</param>
/// <param name="Truncated">
/// True when the response ended inside a record. Routine rather than exceptional: <c>maxBytes</c>
/// cuts on a byte boundary, so a full response normally ends this way.
/// </param>
/// <param name="TruncatedRecordBytes">
/// How big the dropped record is, when its header made it into the response; 0 when the response
/// stopped inside the header itself.
/// </param>
public readonly record struct Fetched(
    long HighWatermark,
    IReadOnlyList<byte[]> Records,
    bool Truncated,
    int TruncatedRecordBytes);

/// <summary>Reads one partition of one topic, forward.</summary>
/// <remarks>
/// <para>
/// <b>The position lives here, not in the broker.</b> That is half the reason this project has no
/// consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
/// already knows, and asking a broker to remember it is what drags in cluster consensus. The cost is
/// that a restarting consumer has to be told where to resume — <see cref="Position"/> is the number
/// to write down, and writing it down <em>after</em> the records are dealt with rather than before
/// is what makes a restart re-deliver instead of skip.
/// </para>
/// <para>
/// <b>Not safe for concurrent use.</b> Every <see cref="PollAsync"/> advances the position, and the
/// connection matches responses to requests in the order they were sent. One consumer, one
/// partition, one caller.
/// </para>
/// </remarks>
public sealed class Consumer(Connection connection, string topic, int partition, long start = 0)
{
    /// <summary>
    /// 1 MiB: large enough that a fetch is worth its round trip, small enough that one response
    /// cannot dominate a small process. Every client in this repository uses the same number.
    /// </summary>
    public const int DefaultMaxBytes = 1 << 20;

    /// <summary>
    /// Five seconds. A caught-up consumer with no wait asks again immediately and gets nothing,
    /// which is a busy loop dressed as a poll — measured at about two thousand pointless requests a
    /// second (benchmarking, measurement 24). Waiting costs new records nothing: the broker answers
    /// the moment one lands, not when the timer runs out.
    /// </summary>
    public const int DefaultMaxWaitMillis = 5_000;

    /// <summary>The offset of the next record this consumer will read. This is the number to persist.</summary>
    public long Position { get; private set; } = start;

    /// <summary>
    /// Where the log ended at the last successful poll, and 0 before the first one. A snapshot
    /// rather than a live number: by the time it is read, the log may have grown.
    /// </summary>
    public long HighWatermark { get; private set; }

    /// <summary>How many records this consumer was behind at the last poll. Same snapshot caveat.</summary>
    public long Lag => Math.Max(0, HighWatermark - Position);

    public int MaxBytes { get; set; } = DefaultMaxBytes;

    public int MaxWaitMillis { get; set; } = DefaultMaxWaitMillis;

    public int MinBytes { get; set; }

    /// <summary>Moves the read position. Anything fetched and not yet returned is simply forgotten.</summary>
    public void Seek(long offset) => Position = offset;

    /// <summary>Reads the next records and advances <see cref="Position"/> past them.</summary>
    /// <remarks>
    /// <para>
    /// <b>An empty list is not the end of anything.</b> A consumer that has caught up polls at the
    /// high watermark and is answered with no records, which is the steady state of every consumer
    /// keeping up; treating it as the end of the log is how a consumer stops for ever without
    /// erroring.
    /// </para>
    /// <para>
    /// The position advances past whole records only. A response can stop inside a record, because
    /// <c>maxBytes</c> cuts on a byte boundary; the partial tail is dropped and the next poll asks
    /// for that record again from its start. The broker will not do it for us — finding the record
    /// boundary means parsing the batch, which is the work the zero-copy read path exists to avoid.
    /// </para>
    /// </remarks>
    /// <exception cref="RecordExceedsMaxBytesException">
    /// Nothing whole came back and something partial did: the next record is larger than this
    /// consumer is willing to receive, so retrying is what a stall looks like from the inside.
    /// </exception>
    public async Task<IReadOnlyList<byte[]>> PollAsync(CancellationToken token = default)
    {
        var answer = await connection
            .FetchAsync(topic, partition, Position, MaxBytes, MaxWaitMillis, MinBytes, token)
            .ConfigureAwait(false);

        if (answer.Records.Count == 0 && answer.Truncated)
        {
            throw new RecordExceedsMaxBytesException(Position, answer.TruncatedRecordBytes, MaxBytes);
        }

        HighWatermark = answer.HighWatermark;
        Position += answer.Records.Count;
        return answer.Records;
    }

    /// <summary><see cref="PollAsync"/> as an async stream, yielding records one at a time.</summary>
    /// <remarks>
    /// <para>
    /// <code>
    /// await foreach (var record in consumer.RecordsAsync(token))
    /// {
    ///     await HandleAsync(record);
    /// }
    /// </code>
    /// </para>
    /// <para>
    /// <b>The loop does not end</b>, and that is the shape of the thing rather than an oversight: a
    /// partition has no end, only a place it has not been written to yet. <c>break</c> out of it, or
    /// cancel the token — cancellation comes out as <see cref="OperationCanceledException"/> from
    /// the await inside, which is what cancelling is supposed to look like in this runtime.
    /// </para>
    /// <para>
    /// An <c>IAsyncEnumerable</c> and not an event: an event would push records at whatever rate
    /// they arrive with no way for the handler to say it is not ready, so the position would run
    /// ahead of what has actually been processed, and an exception in a handler would have nowhere
    /// to go. <c>await foreach</c> applies back-pressure by construction — the next fetch does not
    /// happen until the body of the loop is done.
    /// </para>
    /// <para>
    /// The position advances a whole fetch at a time, not a record at a time. Breaking out mid-batch
    /// and persisting <see cref="Position"/> skips the rest of that batch, so persist after the
    /// loop, or count what was handled.
    /// </para>
    /// </remarks>
    public async IAsyncEnumerable<byte[]> RecordsAsync(
        [EnumeratorCancellation] CancellationToken token = default)
    {
        while (true)
        {
            foreach (var record in await PollAsync(token).ConfigureAwait(false))
            {
                yield return record;
            }
        }
    }

    // payloadSize and crc32c, in front of every record — the on-disk format unchanged, which is what
    // lets the broker send segment bytes without touching them.
    private const int RecordHeaderBytes = 8;

    /// <summary>Unframes a FETCH response body and verifies every checksum.</summary>
    /// <remarks>
    /// <paramref name="offset"/> is the one that was asked for, and it is here only so that a failure
    /// can say <em>which</em> record is damaged rather than that one of them is.
    /// </remarks>
    internal static Fetched Decode(byte[] body, long offset)
    {
        const int headerBytes = 8 + 4;
        if (body.Length < headerBytes)
        {
            throw new ProtocolException($"FETCH response is {body.Length} bytes, expected at least {headerBytes}");
        }

        var highWatermark = BinaryPrimitives.ReadInt64BigEndian(body);
        var promised = BinaryPrimitives.ReadInt32BigEndian(body.AsSpan(8));
        var payload = body.AsSpan(headerBytes);

        // The frame length already bounds the payload, so this field is redundant — which is exactly
        // what makes it worth checking. It is computed before the transfer starts, while the bytes
        // arrive afterwards from `transferTo` in an unpredictable number of pieces; a disagreement
        // means the two halves of the response came from different states of the log.
        if (promised != payload.Length)
        {
            throw new ProtocolException(
                $"FETCH promised {promised} payload bytes and the frame carries {payload.Length}");
        }

        var records = new List<byte[]>();
        var cursor = 0;

        while (payload.Length - cursor >= RecordHeaderBytes)
        {
            var size = BinaryPrimitives.ReadInt32BigEndian(payload[cursor..]);
            // ReadUInt32 because the sum is unsigned. Unlike Python and JavaScript, C# does **not**
            // punish getting this wrong: `(uint)ReadInt32BigEndian(...)` is a bit-preserving
            // reinterpretation in the default unchecked context, and comparing two ints compares the
            // same bits — swapping the two here changes nothing, which was checked by mutation
            // rather than assumed. The trap that does exist is widening a signed sum to long, where
            // sign extension turns 0x82F63B78 into something no uint ever equals.
            var stored = BinaryPrimitives.ReadUInt32BigEndian(payload[(cursor + 4)..]);

            // A whole header is either there or not — parsing always resumes on a record boundary —
            // so a non-positive size is a malformed frame rather than a truncated tail. Empty
            // records cannot be stored at all, which is why the broker refuses them.
            if (size <= 0)
            {
                throw new ProtocolException($"record header at offset {offset + records.Count} says {size} bytes");
            }

            if (size > payload.Length - cursor - RecordHeaderBytes)
            {
                return new Fetched(highWatermark, records, true, size);
            }

            var start = cursor + RecordHeaderBytes;
            var record = payload[start..(start + size)].ToArray();

            // After the length check and never before it: a truncated tail is not corruption, and
            // reporting it as such would turn the most ordinary response there is into an alarm.
            var computed = Crc32C.Compute(record);
            if (computed != stored)
            {
                throw new CorruptRecordException(offset + records.Count, stored, computed);
            }

            records.Add(record);
            cursor = start + size;
        }

        // Fewer bytes left than a record header: the response stopped inside the header of the next
        // record, which is the same truncation with nothing to say about its size.
        return new Fetched(highWatermark, records, cursor < payload.Length, 0);
    }
}
