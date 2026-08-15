"""What every booblik client has to get right, checked from the outside.

Each check is chosen because it fails **silently** when it fails at all. Nothing here checks that a
correct client is correct; the harness exists for the class of bug that produces a running, plausible
client which loses records, reorders them, or stalls — and none of which shows up as an error.

Every producer check is verified by `wire.py`, never by the client under test. Reading a Go
producer's records back with that same Go producer proves it agrees with itself, which is exactly
what a wrong partitioner also does.

The broker is expected to have been started with:

    BOOBLIK_TOPICS=conformance:3,single:1

**Three and not four, and that is measured rather than tidy.** A partition count that is a power of
two folds on the low bits of the hash, and the low bit of FNV-1a and the low bit of
`Arrays.hashCode` are the *same function* — both reduce to the parity of the low bits of the key's
bytes, because 0x01000193 and 31 are odd. Over 200 000 random keys the two algorithms picked the
same partition 52.94% of the time at four partitions against the 25% chance would give, 26.24% at
eight against 12.50%, and 33.40% at three against 33.33% — chance exactly. So a four-partition topic
halves the power of the strongest check here against the likeliest mistake of all, porting the old
algorithm. Three costs nothing and restores it.

(The same measurement says the *distribution* is fine either way — under 2% off even at sixteen
partitions for both algorithms. This is about what the check can detect, not about the partitioner.)

One partition in `single` because several checks need a place whose offsets nothing else moves.
"""

import wire
from client import ClientError

TOPIC = "conformance"
PARTITIONS = 3
SINGLE = "single"

#: Vector column to read for a three-partition topic.
VECTOR_COLUMN = "p3"

CHECKS = []


def check(role: str, title: str):
    def register(function):
        CHECKS.append({"role": role, "title": title, "run": function})
        return function

    return register


class Context:
    def __init__(self, client, host, port, vectors):
        self.client = client
        self.host = host
        self.port = port
        self.vectors = vectors

    def connect(self):
        return wire.Connection(self.host, self.port)

    def watermark(self, topic: str, partition: int) -> int:
        with self.connect() as connection:
            return connection.metadata([topic])[topic][partition]["highWatermark"]

    def read_from(self, topic: str, partition: int, start: int) -> list:
        return wire.read_all(self.host, self.port, topic, partition, start)


# -- producer ------------------------------------------------------------------------------------


@check("producer", "metadata reports the partitions the broker actually has")
def metadata_matches(context):
    answer = context.client.call("metadata", TOPIC)
    reported = {int(line.split()[0]) for line in answer.get("partition", [])}
    with context.connect() as connection:
        actual = set(connection.metadata([TOPIC])[TOPIC])
    assert reported == actual, f"client says {sorted(reported)}, broker has {sorted(actual)}"


@check("producer", "a record arrives byte for byte, every byte value included")
def record_round_trip(context):
    payload = bytes(range(256))
    before = context.watermark(SINGLE, 0)
    context.client.one("produce", SINGLE, 0, "written", payload.hex())

    arrived = context.read_from(SINGLE, 0, before)
    assert len(arrived) == 1, f"expected one record after offset {before}, read {len(arrived)}"
    assert arrived[0] == payload, (
        f"payload changed in transit: sent {len(payload)} bytes, got {len(arrived[0])}; "
        f"first difference at byte {first_difference(payload, arrived[0])}"
    )


@check("producer", "an empty record is refused, and the client passes the refusal on")
def empty_record_is_refused(context):
    """Zero-length records are not storable, and this check exists because that is surprising.

    Recovery reads a record's length prefix before anything else and stops at the first record whose
    bytes do not match their header. A length of zero is indistinguishable from unwritten space in a
    freshly allocated segment, so a stored empty record would end the log at itself on the next
    restart. The broker therefore answers `CORRUPT_REQUEST` (`RequestDecoder`: "empty records are
    not storable").

    The first run of this harness found that the protocol document did not say so — which is what an
    independent reading is for. What is checked here is the client's part: a refusal has to reach
    the caller. Swallowing it loses records silently, which is worse than the restriction.
    """
    before = context.watermark(SINGLE, 0)
    answer = context.client.one("produce", SINGLE, 0, "written", "")

    assert answer.get("error") == "CORRUPT_REQUEST", (
        f"expected CORRUPT_REQUEST for a zero-length record, client answered {answer}"
    )
    assert context.watermark(SINGLE, 0) == before, "the refused record was written anyway"


@check("producer", "a batch lands contiguously and in order")
def batch_is_contiguous(context):
    payloads = [f"batch-{index}".encode() for index in range(10)]
    before = context.watermark(SINGLE, 0)

    answer = context.client.one("produce", SINGLE, 0, "written", ",".join(p.hex() for p in payloads))
    base = int(answer["baseOffset"])
    assert base == before, f"baseOffset {base} but the partition ended at {before}"

    arrived = context.read_from(SINGLE, 0, before)
    assert arrived == payloads, "records came back in a different order or with gaps"


@check("producer", "ack=none returns without waiting for an answer that never comes")
def ack_none_does_not_wait(context):
    """The single most common way to write a first producer in a new language is to wait here.

    With `AckPolicy.NONE` the broker sends nothing at all — not an empty response, nothing — because
    no offset exists until the writer reaches the batch. A client that reads a response after this
    request blocks for ever, or worse, reads the *next* request's response and matches it to the
    wrong caller. Five seconds is generous; the failure mode is unbounded.
    """
    before = context.watermark(SINGLE, 0)
    context.client.call("produce", SINGLE, 0, "none", b"unacknowledged".hex(), timeout=5.0)

    # And it still has to arrive: "no acknowledgement" is not "no write".
    for _ in range(40):
        if context.watermark(SINGLE, 0) > before:
            return
        __import__("time").sleep(0.25)
    raise AssertionError("the record was never written, so the client did not send it")


@check("producer", "ack=forced is acknowledged with an offset")
def ack_forced(context):
    before = context.watermark(SINGLE, 0)
    answer = context.client.one("produce", SINGLE, 0, "forced", b"durable".hex())
    assert int(answer["baseOffset"]) == before, "forced write reported the wrong base offset"


@check("producer", "the partitioner agrees with the vectors, through a real broker")
def partitioner_matches_vectors(context):
    """The check the whole conformance kit was started for.

    Two things are asserted and both are needed: that the client *says* it chose the partition the
    vectors specify, and that the record *is* in that partition when somebody else looks. A client
    can report one and write to another — that is precisely what an off-by-one in the fold does —
    and either half alone would miss it.
    """
    mismatches = []
    for vector in context.vectors:
        key = bytes.fromhex(vector["keyHex"])
        expected = int(vector[VECTOR_COLUMN])
        payload = b"keyed:" + key

        before = context.watermark(TOPIC, expected)
        answer = context.client.one("produce-keyed", TOPIC, vector["keyHex"], payload.hex())
        chosen = int(answer["partition"])

        if chosen != expected:
            mismatches.append(f"«{vector['name']}»: chose {chosen}, vectors say {expected}")
            continue

        arrived = context.read_from(TOPIC, expected, before)
        if payload not in arrived:
            mismatches.append(f"«{vector['name']}»: said {chosen} but the record is not there")

    assert not mismatches, "the partitioner disagrees with the specification:\n      " + "\n      ".join(mismatches)


@check("producer", "an unknown topic is refused, not crashed")
def unknown_topic(context):
    answer = context.client.one("produce", "no-such-topic", 0, "written", b"x".hex())
    assert answer.get("error") == "UNKNOWN_TOPIC_OR_PARTITION", (
        f"expected UNKNOWN_TOPIC_OR_PARTITION, client answered {answer}"
    )


@check("producer", "a partition that does not exist is refused")
def unknown_partition(context):
    answer = context.client.one("produce", TOPIC, PARTITIONS + 5, "written", b"x".hex())
    assert answer.get("error") == "UNKNOWN_TOPIC_OR_PARTITION", (
        f"expected UNKNOWN_TOPIC_OR_PARTITION, client answered {answer}"
    )


# -- consumer ------------------------------------------------------------------------------------


@check("consumer", "fetch returns exactly what was produced")
def fetch_round_trip(context):
    # No zero-length record here: the broker refuses those outright, which `empty_record_is_refused`
    # covers. A single byte is the smallest thing this path can actually carry.
    payloads = [bytes(range(256)), b"\x00", b"second"]
    with context.connect() as connection:
        before = connection.metadata([SINGLE])[SINGLE][0]["highWatermark"]
        connection.produce(SINGLE, 0, payloads)

    answer = context.client.call("fetch", SINGLE, 0, before, 1 << 20)
    arrived = [bytes.fromhex(hex_value) for hex_value in answer.get("record", [])]
    assert arrived == payloads, f"expected {payloads!r}, client returned {arrived!r}"


@check("consumer", "fetching at the high watermark is empty, not an error")
def fetch_at_the_end(context):
    end = context.watermark(SINGLE, 0)
    answer = context.client.call("fetch", SINGLE, 0, end, 1 << 20)
    assert "error" not in answer, (
        f"a caught-up consumer is normal, but the client reported {answer.get('error')}"
    )
    assert not answer.get("record"), "the broker has nothing past the high watermark to return"


@check("consumer", "a truncated tail is dropped rather than returned")
def truncated_tail(context):
    """`maxBytes` bounds the response in bytes, not in records, so a response can stop mid-record.

    A client that returns the fragment corrupts data; one that treats it as the end of the log
    stalls for ever without erroring. The bound here is chosen to land inside the second record.
    """
    payloads = [b"A" * 100, b"B" * 100, b"C" * 100]
    with context.connect() as connection:
        before = connection.metadata([SINGLE])[SINGLE][0]["highWatermark"]
        connection.produce(SINGLE, 0, payloads)

    # One whole record is 8 bytes of header plus 100 of payload; 150 stops inside the second.
    answer = context.client.call("fetch", SINGLE, 0, before, 150)
    arrived = [bytes.fromhex(hex_value) for hex_value in answer.get("record", [])]

    assert arrived, "returned nothing, so a consumer would stall here for ever"
    assert all(record in payloads for record in arrived), "returned a partial record as if whole"
    assert arrived == payloads[: len(arrived)], "returned records out of order"


@check("consumer", "a record too large for maxBytes is reported, not read as the end of the log")
def record_larger_than_max_bytes(context):
    """The second way a consumer stalls, and the only one that never resolves itself.

    A record bigger than `maxBytes` comes back as a truncated tail with nothing whole before it. The
    correct response to a truncated tail is to drop it and ask again — which here produces the
    identical request, for ever. The consumer keeps running, reports nothing and never advances, and
    from the outside that is indistinguishable from a consumer that has caught up.

    The distinction is available to the client and to nobody else: at the high watermark the
    response is empty *and whole*; here it is empty *and truncated*. A client that does not tell the
    caller has thrown that away.
    """
    payload = b"X" * 500
    with context.connect() as connection:
        before = connection.metadata([SINGLE])[SINGLE][0]["highWatermark"]
        connection.produce(SINGLE, 0, [payload])

    answer = context.client.call("fetch", SINGLE, 0, before, 100)

    reported = answer.get("recordExceedsMaxBytes", [])
    assert reported, (
        "a 500-byte record was fetched with maxBytes=100 and the client answered "
        f"{answer} — a caller cannot tell this from having caught up"
    )
    assert int(reported[0]) == len(payload), (
        f"reported {reported[0]} bytes for a record of {len(payload)}; the number is what a caller "
        "raises maxBytes to"
    )
    assert not answer.get("record"), "a record that does not fit came back anyway"


@check("consumer", "an offset past the end is refused")
def offset_out_of_range(context):
    end = context.watermark(SINGLE, 0)
    answer = context.client.call("fetch", SINGLE, 0, end + 1000, 1 << 20)
    codes = answer.get("error", [])
    assert codes and codes[0] == "OFFSET_OUT_OF_RANGE", (
        f"expected OFFSET_OUT_OF_RANGE, client answered {answer}"
    )


def first_difference(expected: bytes, actual: bytes):
    for index, (left, right) in enumerate(zip(expected, actual)):
        if left != right:
            return index
    return min(len(expected), len(actual))


__all__ = ["CHECKS", "Context", "ClientError", "TOPIC", "SINGLE", "PARTITIONS"]
