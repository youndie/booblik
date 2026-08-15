using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text;

namespace Booblik;

/// <summary>How long the broker waits before answering.</summary>
public enum AckPolicy : byte
{
    /// <summary>
    /// Answers nothing at all — not an empty response, nothing, because no offset exists until the
    /// writer reaches the batch. It is also the only mode in which the broker may lose an accepted
    /// record without the client hearing about it, or about being overloaded.
    /// </summary>
    None = 0,

    /// <summary>Answers once the record is in the log, before any durability barrier.</summary>
    Written = 1,

    /// <summary>Answers after force(). Not one barrier per request: the broker groups everyone queued.</summary>
    Forced = 2,
}

/// <param name="LogStartOffset">
/// Where the <em>live</em> log begins, after retention. Reading "from the start" means starting here
/// and not at zero — zero is OFFSET_OUT_OF_RANGE on any topic that has ever dropped a segment.
/// </param>
/// <param name="HighWatermark">The first offset that does not exist yet.</param>
public readonly record struct PartitionInfo(int Partition, long LogStartOffset, long HighWatermark);

/// <summary>The offsets a batch was given. The records are contiguous from <see cref="BaseOffset"/>.</summary>
public readonly record struct ProduceResult(long BaseOffset, long LogEndOffset);

/// <summary>One connection to a broker.</summary>
/// <remarks>
/// <b>Not safe for concurrent use.</b> Requests and responses are matched by a correlation id in the
/// order they were sent, so two callers sharing a Connection would read each other's answers. Use one
/// per caller, or a <see cref="Producer"/>, which owns its own.
/// </remarks>
public sealed class Connection : IDisposable
{
    private const short ApiProduce = 1;
    private const short ApiFetch = 2;
    private const short ApiMetadata = 3;
    private const short Version = 1;

    // FETCH alone has a v2, which adds maxWaitMillis and minBytes. PRODUCE and METADATA have no v2.
    private const short FetchVersion = 2;

    // What the length prefix counts before the payload: apiKey, apiVersion, correlationId.
    private const int RequestHeaderBytes = 2 + 2 + 4;

    // correlationId and errorCode, on every response.
    private const int ResponseHeaderBytes = 4 + 2;

    private const int MaxFrameBytes = 8 << 20;

    private readonly TcpClient _client;
    private readonly NetworkStream _stream;
    private int _correlation;

    private Connection(TcpClient client)
    {
        _client = client;
        _stream = client.GetStream();
    }

    /// <param name="address"><c>host:port</c>, which is the form every configuration uses.</param>
    public static async Task<Connection> ConnectAsync(string address, CancellationToken token = default)
    {
        var separator = address.LastIndexOf(':');
        var host = address[..separator];
        var port = int.Parse(address[(separator + 1)..]);

        var client = new TcpClient { NoDelay = true };
        await client.ConnectAsync(host, port, token).ConfigureAwait(false);
        return new Connection(client);
    }

    public void Dispose()
    {
        _stream.Dispose();
        _client.Dispose();
    }

    // -- framing ---------------------------------------------------------------------------------

    private async Task<int> SendAsync(short apiKey, short version, byte[] payload, CancellationToken token)
    {
        var correlation = ++_correlation;
        var frameLength = RequestHeaderBytes + payload.Length;

        var frame = new byte[4 + frameLength];
        BinaryPrimitives.WriteInt32BigEndian(frame, frameLength);
        BinaryPrimitives.WriteInt16BigEndian(frame.AsSpan(4), apiKey);
        BinaryPrimitives.WriteInt16BigEndian(frame.AsSpan(6), version);
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(8), correlation);
        payload.CopyTo(frame.AsSpan(12));

        await _stream.WriteAsync(frame, token).ConfigureAwait(false);
        return correlation;
    }

    private async Task<byte[]> ReceiveAsync(int expect, CancellationToken token)
    {
        var prefix = new byte[4];
        await _stream.ReadExactlyAsync(prefix, token).ConfigureAwait(false);

        var length = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (length is < ResponseHeaderBytes or > MaxFrameBytes)
        {
            throw new ProtocolException($"response frame length {length} is out of range");
        }

        var frame = new byte[length];
        await _stream.ReadExactlyAsync(frame, token).ConfigureAwait(false);

        var correlation = BinaryPrimitives.ReadInt32BigEndian(frame);
        var code = (Code)BinaryPrimitives.ReadInt16BigEndian(frame.AsSpan(4));

        // Checked rather than trusted. A response carrying somebody else's correlation id does not
        // merely lose information — it **resolves the wrong request**, handing one caller another
        // caller's offsets.
        if (correlation != expect)
        {
            throw new ProtocolException($"response {correlation} answered request {expect}");
        }

        if (code != Code.None)
        {
            throw new BrokerException(code);
        }

        return frame[ResponseHeaderBytes..];
    }

    // -- requests --------------------------------------------------------------------------------

    /// <summary>Which topics exist and where each partition currently starts and ends.</summary>
    /// <remarks>
    /// No names asks for everything. A named topic that does not exist fails the whole request with
    /// UNKNOWN_TOPIC_OR_PARTITION rather than being left out of the answer — otherwise "no such
    /// topic" and "the topic is empty" would arrive looking identical.
    /// </remarks>
    public async Task<Dictionary<string, List<PartitionInfo>>> MetadataAsync(
        string[] topics,
        CancellationToken token = default)
    {
        var names = topics.Select(Encoding.UTF8.GetBytes).ToArray();
        var payload = new byte[4 + names.Sum(name => 2 + name.Length)];

        BinaryPrimitives.WriteInt32BigEndian(payload, names.Length);
        var offset = 4;
        foreach (var name in names)
        {
            BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(offset), (ushort)name.Length);
            offset += 2;
            name.CopyTo(payload.AsSpan(offset));
            offset += name.Length;
        }

        var correlation = await SendAsync(ApiMetadata, Version, payload, token).ConfigureAwait(false);
        return DecodeMetadata(await ReceiveAsync(correlation, token).ConfigureAwait(false));
    }

    /// <summary>Appends records to one partition as a single request.</summary>
    /// <remarks>
    /// <para>
    /// They land <b>contiguously</b> — one request is written by one call into that partition's
    /// writer, so the offsets run from <c>BaseOffset</c> with nothing interleaved. It is <b>not</b>
    /// atomic: a crash mid-write leaves whatever reached the disk, and recovery keeps the prefix
    /// that passes its checksums. There are no transactions in this broker.
    /// </para>
    /// <para>
    /// Returns <c>null</c> under <see cref="AckPolicy.None"/>, because no answer is coming.
    /// </para>
    /// <para>
    /// Nothing here validates record sizes. The broker refuses empty records and empty batches with
    /// CORRUPT_REQUEST, and duplicating that rule client-side would create a second place to
    /// disagree with it — the rule belongs to whatever has to store the result.
    /// </para>
    /// </remarks>
    public async Task<ProduceResult?> ProduceAsync(
        string topic,
        int partition,
        IReadOnlyList<byte[]> records,
        AckPolicy ack = AckPolicy.Written,
        CancellationToken token = default)
    {
        var name = Encoding.UTF8.GetBytes(topic);
        var size = 2 + name.Length + 4 + 1 + 4 + records.Sum(record => 4 + record.Length);
        var payload = new byte[size];

        BinaryPrimitives.WriteUInt16BigEndian(payload, (ushort)name.Length);
        var offset = 2;
        name.CopyTo(payload.AsSpan(offset));
        offset += name.Length;

        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(offset), partition);
        offset += 4;
        payload[offset++] = (byte)ack;
        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(offset), records.Count);
        offset += 4;

        foreach (var record in records)
        {
            BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(offset), record.Length);
            offset += 4;
            record.CopyTo(payload.AsSpan(offset));
            offset += record.Length;
        }

        var correlation = await SendAsync(ApiProduce, Version, payload, token).ConfigureAwait(false);
        if (ack == AckPolicy.None)
        {
            return null;
        }

        var body = await ReceiveAsync(correlation, token).ConfigureAwait(false);
        return new ProduceResult(
            BinaryPrimitives.ReadInt64BigEndian(body),
            BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(8)));
    }

    /// <summary>Reads records from one partition, checksum-verified.</summary>
    /// <remarks>
    /// <para>
    /// <paramref name="maxBytes"/> bounds the response <b>in bytes, not in records</b>, so it can
    /// stop inside one; see <see cref="Fetched.Truncated"/>. <paramref name="maxWaitMillis"/> is how
    /// long the broker may hold a request that has nothing to answer with, and 0 — the default here,
    /// though not in <see cref="Consumer"/> — returns at once.
    /// </para>
    /// <para>
    /// <paramref name="minBytes"/> greater than <paramref name="maxBytes"/> is CORRUPT_REQUEST: a
    /// request that can never be satisfied. That is left to the broker rather than checked here, so
    /// there is one place that decides it instead of two that can disagree.
    /// </para>
    /// <para>
    /// Always v2, including when nothing is being waited for. One code path rather than two: a
    /// client that switched versions depending on its arguments would exercise v1 only in the branch
    /// nobody debugs. The broker still decodes v1 for anybody else's client.
    /// </para>
    /// </remarks>
    public async Task<Fetched> FetchAsync(
        string topic,
        int partition,
        long offset,
        int maxBytes = Consumer.DefaultMaxBytes,
        int maxWaitMillis = 0,
        int minBytes = 0,
        CancellationToken token = default)
    {
        var name = Encoding.UTF8.GetBytes(topic);
        var payload = new byte[2 + name.Length + 4 + 8 + 4 + 4 + 4];

        BinaryPrimitives.WriteUInt16BigEndian(payload, (ushort)name.Length);
        var cursor = 2;
        name.CopyTo(payload.AsSpan(cursor));
        cursor += name.Length;

        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(cursor), partition);
        BinaryPrimitives.WriteInt64BigEndian(payload.AsSpan(cursor + 4), offset);
        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(cursor + 12), maxBytes);
        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(cursor + 16), maxWaitMillis);
        BinaryPrimitives.WriteInt32BigEndian(payload.AsSpan(cursor + 20), minBytes);

        var correlation = await SendAsync(ApiFetch, FetchVersion, payload, token).ConfigureAwait(false);
        return Consumer.Decode(await ReceiveAsync(correlation, token).ConfigureAwait(false), offset);
    }

    /// <summary>A <see cref="Consumer"/> reading this partition from <paramref name="start"/>.</summary>
    /// <remarks>
    /// Reading "from the beginning" means starting at the partition's <c>LogStartOffset</c> from
    /// <see cref="MetadataAsync"/>, not at zero: zero is OFFSET_OUT_OF_RANGE on any topic that has
    /// ever dropped a segment to retention. Reading "only what is new" means its
    /// <c>HighWatermark</c>.
    /// </remarks>
    /// <remarks>
    /// Named <c>CreateConsumer</c> and not <c>Consumer</c>: a method whose name matches a type in
    /// scope shadows that type at its own declaration, and the compiler reads the return type as a
    /// reference to the method. Every other client here calls this <c>consumer</c>.
    /// </remarks>
    public Consumer CreateConsumer(string topic, int partition, long start = 0) =>
        new(this, topic, partition, start);

    /// <summary>A handle with the topic's partitions taken from the broker rather than an argument.</summary>
    /// <remarks>
    /// Asking is the point. A partition count supplied by hand can disagree with the broker, and what
    /// that produces — records piling into the partitions that exist while others are never written
    /// to — reads as a data problem rather than the configuration mistake it is.
    /// </remarks>
    public async Task<Topic> TopicAsync(string name, CancellationToken token = default)
    {
        var answer = await MetadataAsync([name], token).ConfigureAwait(false);
        if (!answer.TryGetValue(name, out var infos) || infos.Count == 0)
        {
            throw new ProtocolException($"broker has no partitions for {name}");
        }

        return new Topic(this, name, infos.Select(info => info.Partition).ToArray());
    }

    internal static Dictionary<string, List<PartitionInfo>> DecodeMetadata(byte[] body)
    {
        var result = new Dictionary<string, List<PartitionInfo>>();
        var cursor = 0;

        try
        {
            var topicCount = BinaryPrimitives.ReadInt32BigEndian(body);
            cursor += 4;

            for (var index = 0; index < topicCount; index++)
            {
                var nameLength = BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(cursor));
                cursor += 2;
                var name = Encoding.UTF8.GetString(body, cursor, nameLength);
                cursor += nameLength;

                var partitionCount = BinaryPrimitives.ReadInt32BigEndian(body.AsSpan(cursor));
                cursor += 4;

                var partitions = new List<PartitionInfo>(partitionCount);
                for (var partition = 0; partition < partitionCount; partition++)
                {
                    partitions.Add(new PartitionInfo(
                        BinaryPrimitives.ReadInt32BigEndian(body.AsSpan(cursor)),
                        BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(cursor + 4)),
                        BinaryPrimitives.ReadInt64BigEndian(body.AsSpan(cursor + 12))));
                    cursor += 20;
                }

                result[name] = partitions;
            }
        }
        catch (ArgumentOutOfRangeException failure)
        {
            // A response cut short by a broker restart is a connection problem, not an exception the
            // caller has never heard of coming out of a decode.
            throw new ProtocolException($"METADATA response ends early: {failure.Message}");
        }

        return result;
    }
}

/// <summary>One topic, so its name and its routing stop being arguments to every call.</summary>
public sealed class Topic(Connection connection, string name, int[] partitions)
{
    private int _roundRobin = -1;

    public string Name { get; } = name;

    public IReadOnlyList<int> Partitions { get; } = partitions;

    /// <summary>Where a record with this key goes.</summary>
    /// <remarks>
    /// A null key takes the next partition round-robin, and that counter advances on every call — so
    /// asking and then sending is two turns of it and the records start skipping partitions. With a
    /// key there is no such thing, the answer being a pure function of the key. Round-robin rather
    /// than random because a handful of unkeyed records should look evenly spread, and random
    /// visibly clumps at small counts in a way that reads as a bug.
    /// </remarks>
    public int PartitionFor(byte[]? key)
    {
        if (key is null)
        {
            var next = Interlocked.Increment(ref _roundRobin);
            return Partitions[(int)((uint)next % (uint)Partitions.Count)];
        }

        return Partitions[Partitioner.PartitionFor(key, Partitions.Count)];
    }

    /// <summary>Publishes one record, choosing its partition from <paramref name="key"/>.</summary>
    /// <remarks>
    /// One record per request throws away the largest single performance factor there is: the
    /// broker's own measurements put batches of a hundred at 4 335 482 records/s against 80 592 one
    /// at a time. Use <see cref="Connection.ProduceAsync"/> with a list, or a <see cref="Producer"/>,
    /// whenever records are available together.
    /// </remarks>
    public Task<ProduceResult?> SendAsync(
        byte[] record,
        byte[]? key = null,
        AckPolicy ack = AckPolicy.Written,
        CancellationToken token = default) =>
        connection.ProduceAsync(Name, PartitionFor(key), [record], ack, token);
}
