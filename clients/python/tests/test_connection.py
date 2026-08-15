import threading
import unittest

from booblik import AckPolicy, BrokerError, Code, Connection, ProtocolError
from booblik.wire import decode_metadata
from booblik.partition import partition_for

from .fake_broker import FakeBroker

ALL_BYTES = bytes(range(256))


class ConnectionTest(unittest.TestCase):
    def setUp(self):
        self.broker = FakeBroker(partitions=3)
        self.addCleanup(self.broker.close)
        self.connection = Connection.connect(self.broker.address)
        self.addCleanup(self.connection.close)

    def test_a_record_arrives_byte_for_byte(self):
        # Every byte value, because an encoding that damages the payload usually damages the high
        # half of it and leaves ASCII intact.
        records = [ALL_BYTES, b"second", b"\x00"]
        result = self.connection.produce("orders", 2, records)

        self.assertEqual(0, result.base_offset)
        self.assertEqual(3, result.log_end_offset)
        self.assertEqual(records, self.broker.records_in("orders", 2))

    def test_ack_none_does_not_wait_for_an_answer(self):
        """The broker sends nothing at all, and a client that reads a response here blocks for
        ever. The bound is what makes this a test rather than a hang."""
        outcome = {}

        def publish():
            outcome["result"] = self.connection.produce("orders", 0, [b"x"], AckPolicy.NONE)

        thread = threading.Thread(target=publish, daemon=True)
        thread.start()
        thread.join(timeout=5)

        self.assertFalse(thread.is_alive(), "produce waited for a response that is never coming")
        self.assertIsNone(outcome["result"], "AckPolicy.NONE has no offset to report")

    def test_a_refusal_is_an_error_and_keeps_the_connection(self):
        self.broker.refuse_with = Code.UNKNOWN_TOPIC_OR_PARTITION
        with self.assertRaises(BrokerError) as caught:
            self.connection.produce("nope", 0, [b"x"])
        self.assertEqual(Code.UNKNOWN_TOPIC_OR_PARTITION, caught.exception.code)

        # Framing was intact, so the connection is still usable — only a frame length out of range
        # closes one. A client that tore the socket down here would turn a refusal into an outage.
        self.broker.refuse_with = Code.NONE
        self.assertIsNotNone(self.connection.produce("orders", 0, [b"x"]))

    def test_metadata_and_key_routing(self):
        topic = self.connection.topic("orders")
        self.assertEqual(3, len(topic.partitions))

        key = b"user-1"
        self.assertEqual(topic.partitions[partition_for(key, 3)], topic.partition_for(key))
        # The same key, every time. This is what makes asking-then-sending safe with a key.
        self.assertEqual({topic.partition_for(key) for _ in range(5)}, {topic.partition_for(key)})

    def test_unkeyed_routing_advances_round_robin(self):
        topic = self.connection.topic("orders")
        seen = [topic.partition_for(None) for _ in range(9)]
        self.assertEqual({0: 3, 1: 3, 2: 3}, {p: seen.count(p) for p in set(seen)})

    def test_a_truncated_response_is_an_error_not_a_crash(self):
        """A response cut short by a broker restart is a connection problem, not an exception the
        caller has never heard of coming out of a decode."""
        with self.assertRaises(ProtocolError):
            decode_metadata(b"\x00\x00\x00\x01\x00\x05")


if __name__ == "__main__":
    unittest.main()
