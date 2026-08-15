using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Booblik.Tests;

/// <summary>
/// A broker for tests: speaks the protocol well enough to answer, and <b>decodes what the client
/// encoded</b> rather than pattern-matching bytes.
/// </summary>
/// <remarks>
/// That is the point of it. An encoding mistake becomes a decode failure here instead of a mystery
/// against a real broker, and the test suite needs no Docker, no network and no fixtures. It is also
/// a separate reading of docs/api/protocol-wire.md from the client it checks.
/// </remarks>
public sealed class FakeBroker : IDisposable
{
    private const short ApiProduce = 1;
    private const short ApiFetch = 2;
    private const short ApiMetadata = 3;

    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _stopping = new();
    private readonly object _gate = new();
    private readonly Dictionary<(string, int), List<byte[]>> _produced = [];

    private long _nextOffset;
    private int _requests;
    private Code _refuseWith = Code.None;

    /// <summary>
    /// Flips a bit in every stored checksum, which is what a damaged segment looks like from the
    /// socket: the bytes arrive, and only the sum disagrees with them.
    /// </summary>
    public bool Corrupt { get; set; }

    /// <summary>The apiVersion of the last request, so a test can assert what was actually sent.</summary>
    public short LastVersion { get; private set; }

    /// <summary>The decoded fields of the last FETCH — this fixture's own reading of the frame.</summary>
    public (string Topic, int Partition, long Offset, int MaxBytes, int MaxWait, int MinBytes)? LastFetch
    {
        get;
        private set;
    }

    public FakeBroker(int partitions = 3)
    {
        Partitions = partitions;
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        _ = Task.Run(AcceptAsync);
    }

    public int Partitions { get; }

    public string Address => $"127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}";

    public Code RefuseWith
    {
        get { lock (_gate) { return _refuseWith; } }
        set { lock (_gate) { _refuseWith = value; } }
    }

    public int Requests
    {
        get { lock (_gate) { return _requests; } }
    }

    public IReadOnlyList<byte[]> RecordsIn(string topic, int partition)
    {
        lock (_gate)
        {
            return _produced.TryGetValue((topic, partition), out var records) ? [.. records] : [];
        }
    }

    public void Dispose()
    {
        _stopping.Cancel();
        _listener.Stop();
        _stopping.Dispose();
    }

    private async Task AcceptAsync()
    {
        while (!_stopping.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stopping.Token);
            }
            catch (Exception)
            {
                return;
            }

            _ = Task.Run(() => ServeAsync(client));
        }
    }

    private async Task ServeAsync(TcpClient client)
    {
        using (client)
        await using (var stream = client.GetStream())
        {
            while (true)
            {
                var prefix = new byte[4];
                try
                {
                    await stream.ReadExactlyAsync(prefix, _stopping.Token);
                }
                catch (Exception)
                {
                    return;
                }

                var frame = new byte[BinaryPrimitives.ReadInt32BigEndian(prefix)];
                try
                {
                    await stream.ReadExactlyAsync(frame, _stopping.Token);
                }
                catch (Exception)
                {
                    return;
                }

                var apiKey = BinaryPrimitives.ReadInt16BigEndian(frame);
                LastVersion = BinaryPrimitives.ReadInt16BigEndian(frame.AsSpan(2));
                var correlation = BinaryPrimitives.ReadInt32BigEndian(frame.AsSpan(4));
                var payload = frame[8..];

                byte[] body;
                if (apiKey == ApiProduce)
                {
                    var (produced, silent) = Produce(payload);
                    if (silent)
                    {
                        continue;
                    }

                    body = produced;
                }
                else if (apiKey == ApiFetch)
                {
                    body = Fetch(payload);
                }
                else if (apiKey == ApiMetadata)
                {
                    body = Metadata(payload);
                }
                else
                {
                    body = [];
                }

                var response = new byte[4 + 6 + body.Length];
                BinaryPrimitives.WriteInt32BigEndian(response, 6 + body.Length);
                BinaryPrimitives.WriteInt32BigEndian(response.AsSpan(4), correlation);
                BinaryPrimitives.WriteInt16BigEndian(response.AsSpan(8), (short)RefuseWith);
                body.CopyTo(response.AsSpan(10));

                await stream.WriteAsync(response, _stopping.Token);
            }
        }
    }

    /// <summary>
    /// Returns the body and whether to stay silent — silence being what <see cref="AckPolicy.None"/>
    /// means on the wire, and the behaviour a client most often gets wrong.
    /// </summary>
    private (byte[] Body, bool Silent) Produce(byte[] payload)
    {
        var cursor = 0;
        var nameLength = BinaryPrimitives.ReadUInt16BigEndian(payload);
        cursor += 2;
        var topic = Encoding.UTF8.GetString(payload, cursor, nameLength);
        cursor += nameLength;

        var partition = BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor));
        cursor += 4;
        var ack = (AckPolicy)payload[cursor];
        cursor += 1;
        var count = BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor));
        cursor += 4;

        var records = new List<byte[]>(count);
        for (var index = 0; index < count; index++)
        {
            var size = BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor));
            cursor += 4;
            records.Add(payload[cursor..(cursor + size)]);
            cursor += size;
        }

        long baseOffset;
        lock (_gate)
        {
            baseOffset = _nextOffset;
            if (_refuseWith == Code.None)
            {
                if (!_produced.TryGetValue((topic, partition), out var existing))
                {
                    existing = [];
                    _produced[(topic, partition)] = existing;
                }

                existing.AddRange(records);
                _nextOffset += records.Count;
            }

            _requests++;
        }

        if (ack == AckPolicy.None)
        {
            return ([], true);
        }

        var body = new byte[16];
        BinaryPrimitives.WriteInt64BigEndian(body, baseOffset);
        BinaryPrimitives.WriteInt64BigEndian(body.AsSpan(8), baseOffset + records.Count);
        return (body, false);
    }

    /// <summary>
    /// Puts records in a partition's log without going through PRODUCE, so a fetch test states what
    /// is there to read instead of arranging for it. Offsets in this fixture are indices into that
    /// list, per partition.
    /// </summary>
    public void Seed(string topic, int partition, params byte[][] records)
    {
        lock (_gate)
        {
            if (!_produced.TryGetValue((topic, partition), out var existing))
            {
                existing = [];
                _produced[(topic, partition)] = existing;
            }

            existing.AddRange(records);
        }
    }

    /// <summary>
    /// Answers from the seeded log, framing records exactly as the disk holds them — payloadSize,
    /// crc32c, payload — and cutting the response at maxBytes <b>in bytes</b>, which is what puts a
    /// partial record at the end of a full response.
    /// </summary>
    /// <remarks>
    /// maxWait is decoded and remembered, then ignored: nothing here can produce a record while a
    /// request waits, so holding one would only make the tests slower.
    /// </remarks>
    private byte[] Fetch(byte[] payload)
    {
        var cursor = 0;
        var nameLength = BinaryPrimitives.ReadUInt16BigEndian(payload);
        cursor += 2;
        var topic = Encoding.UTF8.GetString(payload, cursor, nameLength);
        cursor += nameLength;

        var partition = BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor));
        var offset = BinaryPrimitives.ReadInt64BigEndian(payload.AsSpan(cursor + 4));
        var maxBytes = BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor + 12));
        LastFetch = (
            topic,
            partition,
            offset,
            maxBytes,
            BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor + 16)),
            BinaryPrimitives.ReadInt32BigEndian(payload.AsSpan(cursor + 20)));

        var log = RecordsIn(topic, partition);
        var stream = new List<byte>();
        var scratch = new byte[8];

        foreach (var record in log.Skip((int)offset))
        {
            BinaryPrimitives.WriteInt32BigEndian(scratch, record.Length);
            stream.AddRange(scratch[..4]);
            BinaryPrimitives.WriteUInt32BigEndian(scratch, Crc32C.Compute(record) ^ (Corrupt ? 1u : 0u));
            stream.AddRange(scratch[..4]);
            stream.AddRange(record);
        }

        var payloadBytes = stream.Take(maxBytes).ToArray();
        var body = new byte[12 + payloadBytes.Length];
        BinaryPrimitives.WriteInt64BigEndian(body, log.Count);
        BinaryPrimitives.WriteInt32BigEndian(body.AsSpan(8), payloadBytes.Length);
        payloadBytes.CopyTo(body.AsSpan(12));
        return body;
    }

    private byte[] Metadata(byte[] payload)
    {
        var count = BinaryPrimitives.ReadInt32BigEndian(payload);
        var cursor = 4;
        var names = new List<string>(count);
        for (var index = 0; index < count; index++)
        {
            var nameLength = BinaryPrimitives.ReadUInt16BigEndian(payload.AsSpan(cursor));
            cursor += 2;
            names.Add(Encoding.UTF8.GetString(payload, cursor, nameLength));
            cursor += nameLength;
        }

        if (names.Count == 0)
        {
            names.Add("everything");
        }

        long highWatermark;
        lock (_gate)
        {
            highWatermark = _nextOffset;
        }

        var body = new List<byte>();
        var scratch = new byte[8];

        BinaryPrimitives.WriteInt32BigEndian(scratch, names.Count);
        body.AddRange(scratch[..4]);

        foreach (var name in names)
        {
            var encoded = Encoding.UTF8.GetBytes(name);
            BinaryPrimitives.WriteUInt16BigEndian(scratch, (ushort)encoded.Length);
            body.AddRange(scratch[..2]);
            body.AddRange(encoded);

            BinaryPrimitives.WriteInt32BigEndian(scratch, Partitions);
            body.AddRange(scratch[..4]);

            for (var partition = 0; partition < Partitions; partition++)
            {
                BinaryPrimitives.WriteInt32BigEndian(scratch, partition);
                body.AddRange(scratch[..4]);
                BinaryPrimitives.WriteInt64BigEndian(scratch, 0);
                body.AddRange(scratch);
                BinaryPrimitives.WriteInt64BigEndian(scratch, highWatermark);
                body.AddRange(scratch);
            }
        }

        return [.. body];
    }
}
