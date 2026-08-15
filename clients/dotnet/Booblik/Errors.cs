namespace Booblik;

/// <summary>Why the broker refused a request. Values are on the wire; see docs/api/protocol-wire.md §5.</summary>
public enum Code : short
{
    None = 0,
    UnknownTopicOrPartition = 1,
    OffsetOutOfRange = 2,
    RecordTooLarge = 3,
    UnsupportedVersion = 4,
    CorruptRequest = 5,
}

public static class CodeNames
{
    /// <summary>The name as the protocol spells it, which is what a conformance client reports.</summary>
    public static string WireName(this Code code) => code switch
    {
        Code.None => "NONE",
        Code.UnknownTopicOrPartition => "UNKNOWN_TOPIC_OR_PARTITION",
        Code.OffsetOutOfRange => "OFFSET_OUT_OF_RANGE",
        Code.RecordTooLarge => "RECORD_TOO_LARGE",
        Code.UnsupportedVersion => "UNSUPPORTED_VERSION",
        Code.CorruptRequest => "CORRUPT_REQUEST",
        _ => $"UNKNOWN({(short)code})",
    };
}

/// <summary>A refusal from the broker, as opposed to anything going wrong with the connection.</summary>
/// <remarks>
/// A refusal is a result and not a transport failure: the connection stays usable, because framing
/// was intact — the broker understood the request and declined it. Only a frame length outside the
/// allowed range closes a connection.
/// </remarks>
public sealed class BrokerException(Code code)
    : Exception($"booblik: broker refused the request: {code.WireName()}")
{
    public Code Code { get; } = code;
}

/// <summary>The bytes on the connection do not make sense — a bad length, a short response, a lost socket.</summary>
public sealed class ProtocolException(string message) : Exception($"booblik: {message}");

/// <summary>A record whose bytes do not match the checksum stored with them.</summary>
/// <remarks>
/// The client is the only party that can notice. On the zero-copy read path the broker sends segment
/// bytes to the socket without looking at them — that is what zero-copy means — so the sum is
/// computed once at write time to protect the <b>disk</b>, and verified once at read time, by
/// whoever finally holds the bytes. A client that skips it silently switches off the project's only
/// defence against a corrupted log.
/// </remarks>
public sealed class CorruptRecordException(long offset, uint stored, uint computed)
    : Exception($"booblik: record at offset {offset} fails its checksum: " +
                $"stored 0x{stored:x8}, computed 0x{computed:x8}")
{
    public long Offset { get; } = offset;

    public uint Stored { get; } = stored;

    public uint Computed { get; } = computed;
}

/// <summary>The next record is larger than <c>MaxBytes</c>, so it can never arrive whole.</summary>
/// <remarks>
/// <para>
/// One of the two ways a consumer stalls, and the one that does not resolve itself. A response with
/// no whole records and a truncated tail means the record does not fit; a client that drops the tail
/// and retries makes exactly the same request for ever — running, reporting nothing, never
/// advancing. Thrown rather than retried, because raising <c>MaxBytes</c> is the only fix.
/// </para>
/// <para>
/// Not to be confused with <see cref="Code.RecordTooLarge"/>, which is the broker refusing to
/// <b>store</b> a record too big for a segment. This one is the reader's own limit, chosen by the
/// reader.
/// </para>
/// </remarks>
public sealed class RecordExceedsMaxBytesException(long offset, int recordBytes, int maxBytes)
    : Exception($"booblik: record at offset {offset} needs {recordBytes} bytes and MaxBytes is " +
                $"{maxBytes}, so it can never be read whole")
{
    public long Offset { get; } = offset;

    public int RecordBytes { get; } = recordBytes;

    public int MaxBytes { get; } = maxBytes;
}
