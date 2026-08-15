package ru.workinprogress.booblik.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectionTest {

    @Test
    void aRecordArrivesByteForByte() {
        // Every byte value, because an encoding that damages the payload usually damages the high
        // half of it and leaves ASCII intact.
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            List<byte[]> records = List.of(allBytes(), bytes("second"), new byte[] {0});
            ProduceResult result = connection.produce("orders", 2, records, AckPolicy.WRITTEN);

            assertEquals(0, result.baseOffset());
            assertEquals(3, result.logEndOffset());

            List<byte[]> arrived = broker.recordsIn("orders", 2);
            assertEquals(records.size(), arrived.size());
            for (int index = 0; index < records.size(); index++) {
                assertArrayEquals(records.get(index), arrived.get(index), "record " + index);
            }
        }
    }

    /**
     * The broker sends nothing at all, and a client that reads a response here blocks for ever. The
     * bound is what makes this a test rather than a hang.
     */
    @Test
    void ackNoneDoesNotWaitForAnAnswer() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> assertNull(
                            connection.produce("orders", 0, List.of(bytes("x")), AckPolicy.NONE),
                            "AckPolicy.NONE has no offset to report"));
        }
    }

    @Test
    void aRefusalIsAnErrorAndKeepsTheConnection() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            broker.refuse(Code.UNKNOWN_TOPIC_OR_PARTITION);
            BrokerException refusal =
                    assertThrows(
                            BrokerException.class,
                            () -> connection.produce("nope", 0, List.of(bytes("x")), AckPolicy.WRITTEN));
            assertEquals(Code.UNKNOWN_TOPIC_OR_PARTITION, refusal.code());

            // Framing was intact, so the connection is still usable — only a frame length out of
            // range closes one. A client that tore the socket down here would turn a refusal into
            // an outage.
            broker.refuse(Code.NONE);
            assertNotNull(connection.produce("orders", 0, List.of(bytes("x")), AckPolicy.WRITTEN));
        }
    }

    @Test
    void metadataAndKeyRouting() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            Connection.Topic topic = connection.topic("orders");
            assertEquals(3, topic.partitions().length);

            byte[] key = bytes("user-1");
            assertEquals(topic.partitions()[Partitioner.partitionFor(key, 3)], topic.partitionFor(key));
            // A pure function of the key: this is what makes asking and then sending safe.
            assertEquals(topic.partitionFor(key), topic.partitionFor(key));
        }
    }

    @Test
    void unkeyedRoutingAdvancesRoundRobin() {
        try (FakeBroker broker = new FakeBroker(3);
                Connection connection = Connection.open(broker.address())) {

            Connection.Topic topic = connection.topic("orders");
            Map<Integer, Integer> seen = new HashMap<>();
            for (int index = 0; index < 9; index++) {
                seen.merge(topic.partitionFor(null), 1, Integer::sum);
            }

            assertEquals(3, seen.size(), "nine unkeyed records should touch all three partitions");
            seen.values().forEach(count -> assertEquals(3, count));
        }
    }

    /**
     * A response cut short by a broker restart is a connection problem, not an
     * ArrayIndexOutOfBounds the caller has never heard of coming out of a decode.
     */
    @Test
    void aTruncatedResponseIsAProtocolError() {
        assertThrows(
                ProtocolException.class,
                () -> Connection.decodeMetadata(new byte[] {0, 0, 0, 1, 0, 5}));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] allBytes() {
        byte[] out = new byte[256];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) index;
        }
        return out;
    }
}
