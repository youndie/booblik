// This client under test, driven by conformance/harness.
//
// The contract is in conformance/README.md: verbs on argv, `key=value` on stdout, the broker in
// BOOBLIK_BROKER. Exit 0 means the verb was carried out — **including** when the broker refused it,
// which is a result and is reported as `error=CODE`. A non-zero exit means this program failed.
//
// Declares `producer,consumer`.

using Booblik;

if (args.Length == 0)
{
    await Console.Error.WriteLineAsync("usage: conformance <verb> [args...]  (broker in BOOBLIK_BROKER)");
    return 2;
}

var verb = args[0];

if (verb == "capabilities")
{
    Console.WriteLine("roles=producer,consumer");
    Console.WriteLine("name=dotnet");
    return 0;
}

var address = Environment.GetEnvironmentVariable("BOOBLIK_BROKER");
if (string.IsNullOrEmpty(address))
{
    await Console.Error.WriteLineAsync("BOOBLIK_BROKER is not set (host:port)");
    return 2;
}

using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
using var connection = await Connection.ConnectAsync(address, timeout.Token);

try
{
    switch (verb)
    {
        case "metadata":
            await Metadata(connection, args[1], timeout.Token);
            break;

        case "produce":
            await Produce(connection, args[1], int.Parse(args[2]), args[3], args[4], timeout.Token);
            break;

        case "produce-keyed":
            await ProduceKeyed(connection, args[1], args[2], args[3], timeout.Token);
            break;

        case "fetch":
            await Fetch(connection, args[1], int.Parse(args[2]), long.Parse(args[3]), int.Parse(args[4]), timeout.Token);
            break;

        default:
            await Console.Error.WriteLineAsync($"unknown verb: {verb}");
            return 2;
    }
}
catch (BrokerException refusal)
{
    // A refusal is a result, not a failure of this program: report it and exit zero.
    Console.WriteLine($"error={refusal.Code.WireName()}");
}

return 0;

// The raw call and not a Consumer: the checks are about what one FETCH answers — a truncated tail,
// an empty response at the high watermark, a refusal past the end — and a Consumer would smooth over
// exactly those by advancing a position and waiting.
static async Task Fetch(
    Connection connection,
    string topic,
    int partition,
    long offset,
    int maxBytes,
    CancellationToken token)
{
    var answer = await connection.FetchAsync(topic, partition, offset, maxBytes, token: token);
    Console.WriteLine($"highWatermark={answer.HighWatermark}");

    // Nothing whole and something partial: the next record is bigger than maxBytes and never
    // arrives, so a caller that only sees an empty list cannot tell this from having caught up.
    if (answer.Records.Count == 0 && answer.Truncated)
    {
        Console.WriteLine($"recordExceedsMaxBytes={answer.TruncatedRecordBytes}");
        return;
    }

    foreach (var record in answer.Records)
    {
        Console.WriteLine($"record={Convert.ToHexString(record).ToLowerInvariant()}");
    }
}

static async Task Metadata(Connection connection, string topic, CancellationToken token)
{
    var answer = await connection.MetadataAsync([topic], token);
    if (!answer.TryGetValue(topic, out var infos))
    {
        return;
    }

    foreach (var info in infos)
    {
        Console.WriteLine($"partition={info.Partition} {info.LogStartOffset} {info.HighWatermark}");
    }
}

static async Task Produce(
    Connection connection,
    string topic,
    int partition,
    string ack,
    string records,
    CancellationToken token)
{
    var policy = ack switch
    {
        "none" => AckPolicy.None,
        "written" => AckPolicy.Written,
        "forced" => AckPolicy.Forced,
        _ => throw new ArgumentException($"unknown ack policy {ack}", nameof(ack)),
    };

    // An empty field is a zero-length record and it is passed on rather than tidied away: the broker
    // refuses those, and the harness checks that the refusal reaches the caller.
    var payloads = records.Split(',').Select(Convert.FromHexString).ToArray();

    var result = await connection.ProduceAsync(topic, partition, payloads, policy, token);
    // Null under AckPolicy.None, and printing nothing is the correct answer: no offset exists yet.
    // Awaiting one here is what the harness times out on.
    if (result is { } offsets)
    {
        Console.WriteLine($"baseOffset={offsets.BaseOffset}");
        Console.WriteLine($"logEndOffset={offsets.LogEndOffset}");
    }
}

// Where the partitioner is exercised for real: the partition is chosen here, from the key, because
// the broker never sees the key at all.
static async Task ProduceKeyed(
    Connection connection,
    string name,
    string keyHex,
    string payloadHex,
    CancellationToken token)
{
    var key = Convert.FromHexString(keyHex);
    var topic = await connection.TopicAsync(name, token);
    var chosen = topic.PartitionFor(key);

    var result = await connection.ProduceAsync(
        name, chosen, [Convert.FromHexString(payloadHex)], AckPolicy.Written, token);

    Console.WriteLine($"partition={chosen}");
    Console.WriteLine($"baseOffset={result!.Value.BaseOffset}");
}
