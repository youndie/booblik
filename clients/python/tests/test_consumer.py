import unittest
from pathlib import Path

from booblik import Connection, CorruptRecordError, RecordExceedsMaxBytesError, crc32c
from booblik.errors import BrokerError, Code
from tests.fake_broker import FakeBroker


def read_vectors(name: str) -> list[list[str]]:
    """The golden vectors, found by walking up rather than trusting the working directory.

    ``python -m unittest`` runs from this client's directory and an editor may not; a fixture that
    resolves in one but not the other gets deleted by whoever hits it second.
    """
    directory = Path(__file__).resolve().parent
    while not (directory / "conformance" / "vectors" / name).exists():
        if directory.parent == directory:
            raise AssertionError(f"conformance/vectors/{name} not found above {__file__}")
        directory = directory.parent

    rows = []
    for line in (directory / "conformance" / "vectors" / name).read_text().splitlines():
        if line and not line.startswith("#"):
            # split keeps empty fields, so the empty-payload vector arrives as a leading "" —
            # which is the vector a hand-written parser drops first.
            rows.append(line.split("\t"))
    return rows


class ChecksumTest(unittest.TestCase):
    """Holds this implementation to vectors computed by another one.

    "CRC32" names at least three different functions, all of which return a plausible number, so
    agreeing with an independent reading of the specification is the only property worth asserting.
    If this fails, this code is wrong — not the vectors.
    """

    def test_matches_golden_vectors(self):
        rows = read_vectors("crc32c.tsv")
        self.assertTrue(rows, "no vectors loaded")
        for payload_hex, expected, name in rows:
            with self.subTest(vector=name):
                self.assertEqual(crc32c(bytes.fromhex(payload_hex)), int(expected))

    def test_is_castagnoli_and_not_zlib(self):
        """The one number that separates the two. zlib.crc32 gives 0xCBF43926 for the same input,
        and nothing about the substitution looks wrong from the outside."""
        self.assertEqual(crc32c(b"123456789"), 0xE3069283)

    def test_stays_inside_32_bits(self):
        """Python integers do not overflow, so the mask is the algorithm rather than tidiness."""
        for payload in (b"", b"\x00", bytes(range(256)) * 4):
            self.assertLessEqual(crc32c(payload), 0xFFFFFFFF)


class ConsumerTest(unittest.TestCase):
    def setUp(self):
        self.broker = FakeBroker(partitions=1)
        self.addCleanup(self.broker.close)
        self.connection = Connection.connect(self.broker.address, timeout=5)
        self.addCleanup(self.connection.close)

    def consumer(self, **options):
        # The fixture answers whatever it is asked immediately, so waiting would only be time spent.
        options.setdefault("max_wait_millis", 0)
        return self.connection.consumer("t", 0, 0, **options)

    def test_records_come_back_in_order(self):
        payloads = [bytes(range(256)), b"\x00", b"third"]
        self.broker.seed("t", 0, *payloads)

        consumer = self.consumer()
        self.assertEqual(consumer.poll(), payloads)
        self.assertEqual(consumer.position, 3)
        self.assertEqual(consumer.high_watermark, 3)
        self.assertEqual(consumer.lag, 0)

    def test_the_high_watermark_is_empty_and_not_an_error(self):
        """The steady state of a consumer that is keeping up, and the one it must not read as the
        end of the log."""
        self.broker.seed("t", 0, b"one")
        consumer = self.consumer()
        consumer.poll()

        self.assertEqual(consumer.poll(), [])
        self.assertEqual(consumer.position, 1)

    def test_truncated_tail_is_dropped_and_refetched(self):
        """``max_bytes`` cuts on a byte boundary, so a full response normally ends inside a record.
        Returning the fragment corrupts data; counting it as the end of the log stalls for ever."""
        self.broker.seed("t", 0, b"A" * 100, b"B" * 100)
        # One whole record is 8 bytes of header and 100 of payload; 150 stops inside the second.
        consumer = self.consumer(max_bytes=150)

        self.assertEqual(consumer.poll(), [b"A" * 100])
        self.assertEqual(consumer.position, 1, "the partial record must not be counted")
        self.assertEqual(consumer.poll(), [b"B" * 100], "the dropped record must come back whole")

    def test_response_stopping_inside_a_record_header_is_truncation(self):
        """The other branch: no size field to read, so it is found by having bytes left over."""
        self.broker.seed("t", 0, b"A" * 20, b"B" * 20)
        # 28 bytes is the first record whole, then 4 bytes into the second record's 8-byte header.
        consumer = self.consumer(max_bytes=32)

        self.assertEqual(consumer.poll(), [b"A" * 20])
        self.assertEqual(consumer.position, 1)

    def test_record_larger_than_max_bytes_is_reported(self):
        """The stall that does not resolve itself: every retry makes the identical request."""
        self.broker.seed("t", 0, b"A" * 500)
        consumer = self.consumer(max_bytes=100)

        with self.assertRaises(RecordExceedsMaxBytesError) as caught:
            consumer.poll()
        self.assertEqual(caught.exception.record_bytes, 500)
        self.assertEqual(caught.exception.max_bytes, 100)
        self.assertEqual(caught.exception.offset, 0)
        self.assertEqual(consumer.position, 0, "the position must not move past an unread record")

    def test_corrupt_record_is_rejected(self):
        """The client is the only party that can catch this: on the zero-copy path the broker never
        touches the record bytes it sends."""
        self.broker.seed("t", 0, b"payload")
        self.broker.corrupt = True

        with self.assertRaises(CorruptRecordError) as caught:
            self.consumer().poll()
        self.assertEqual(caught.exception.offset, 0)
        self.assertNotEqual(caught.exception.stored, caught.exception.computed)

    def test_truncation_is_not_reported_as_corruption(self):
        """One line apart in the decoder: the length check has to come before the checksum, or
        every full response is an alarm."""
        self.broker.seed("t", 0, b"A" * 100, b"B" * 100)
        self.assertEqual(self.consumer(max_bytes=150).poll(), [b"A" * 100])

    def test_checksums_above_0x7fffffff_are_read_unsigned(self):
        """struct's ">i" is signed and the sum on the wire is not.

        A payload whose CRC-32C has the high bit set arrives negative if it is unpacked as a signed
        integer, and then matches nothing. Roughly half of all records land here, so getting it
        wrong is not an edge case — it only looks like one until the first run.
        """
        payload = next(
            candidate
            for candidate in (bytes([index]) for index in range(256))
            if crc32c(candidate) > 0x7FFFFFFF
        )
        self.broker.seed("t", 0, payload)
        self.assertEqual(self.consumer().poll(), [payload])

    def test_fetch_goes_out_as_v2_with_the_waiting_fields(self):
        """Always v2, so the waiting fields are not exercised only in the branch nobody debugs.
        Asserted from the broker's side, the only place that can tell what was actually sent."""
        self.broker.seed("t", 0, b"one")
        self.connection.fetch("t", 0, 0, max_bytes=4096, max_wait_millis=250, min_bytes=64)

        self.assertEqual(self.broker.last_version, 2)
        self.assertEqual(
            self.broker.last_fetch,
            {
                "topic": "t",
                "partition": 0,
                "offset": 0,
                "max_bytes": 4096,
                "max_wait": 250,
                "min_bytes": 64,
            },
        )

    def test_a_wait_longer_than_the_socket_timeout_is_refused(self):
        """Python's socket carries one timeout for every operation, so a long fetch can outlive the
        deadline of the very call making it. Refused with the two numbers rather than left to
        surface as a socket.timeout on a request the broker is legitimately still holding."""
        with self.assertRaises(ValueError) as caught:
            self.connection.fetch("t", 0, 0, max_wait_millis=30_000)
        self.assertIn("30000", str(caught.exception))

    def test_refusal_reaches_the_caller(self):
        self.broker.refuse_with = Code.OFFSET_OUT_OF_RANGE
        with self.assertRaises(BrokerError) as caught:
            self.consumer().poll()
        self.assertEqual(caught.exception.code, Code.OFFSET_OUT_OF_RANGE)

    def test_seek_moves_the_position(self):
        self.broker.seed("t", 0, b"zero", b"one", b"two")
        consumer = self.consumer()
        consumer.seek(2)
        self.assertEqual(consumer.poll(), [b"two"])

    def test_records_yields_every_record_once(self):
        seeded = [b"a", b"b", b"c"]
        self.broker.seed("t", 0, *seeded)

        consumer = self.consumer()
        read = []
        for record in consumer.records():
            read.append(record)
            if len(read) == len(seeded):
                break
        self.assertEqual(read, seeded)

    def test_records_is_lazy(self):
        """A generator, so nothing is fetched until the first record is asked for — which is also
        what makes the endless loop affordable."""
        self.broker.seed("t", 0, b"a")
        consumer = self.consumer()
        stream = consumer.records()

        self.assertIsNone(self.broker.last_fetch, "creating the generator already fetched")
        next(stream)
        self.assertIsNotNone(self.broker.last_fetch)


if __name__ == "__main__":
    unittest.main()
