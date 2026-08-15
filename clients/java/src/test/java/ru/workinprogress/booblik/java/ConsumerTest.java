package ru.workinprogress.booblik.java;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsumerTest {

    private FakeBroker broker;
    private Connection connection;

    @BeforeEach
    void start() {
        broker = new FakeBroker(1);
        connection = Connection.open(broker.address());
    }

    @AfterEach
    void stop() {
        connection.close();
        broker.close();
    }

    /** The fixture answers whatever it is asked at once, so waiting would only be time spent. */
    private Consumer consumer() {
        return connection.consumer("t", 0, 0).maxWaitMillis(0);
    }

    private static byte[] repeat(char value, int count) {
        byte[] payload = new byte[count];
        Arrays.fill(payload, (byte) value);
        return payload;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Holds this client to vectors computed by another implementation in another language. "CRC32"
     * names at least three different functions, all of which return a plausible number, so agreeing
     * with an independent reading of the specification is the only property worth asserting.
     *
     * <p>If this fails, this code is wrong — not the vectors.
     */
    @Test
    @DisplayName("the checksum matches the golden vectors")
    void checksumMatchesGoldenVectors() {
        List<String[]> rows = readVectors("crc32c.tsv");
        assertTrue(rows.size() > 0, "no vectors loaded");

        for (String[] row : rows) {
            // The vectors carry the sum unsigned; this client keeps it in an int, which is the same
            // bits and the form the wire uses.
            int expected = (int) Long.parseLong(row[1]);
            assertEquals(expected, Connection.checksum(HexFormat.of().parseHex(row[0])), row[2]);
        }
    }

    /**
     * The one number that separates CRC-32C from the CRC32 a hand reaches for first.
     * {@code java.util.zip.CRC32} — one line away in the same package — gives 0xCBF43926.
     */
    @Test
    @DisplayName("the checksum is Castagnoli and not java.util.zip.CRC32")
    void checksumIsCastagnoli() {
        assertEquals(0xE3069283, Connection.checksum(utf8("123456789")));
    }

    @Test
    @DisplayName("records come back in order and the position moves past them")
    void recordsComeBackInOrder() {
        byte[] allBytes = new byte[256];
        for (int index = 0; index < allBytes.length; index++) {
            allBytes[index] = (byte) index;
        }
        broker.seed("t", 0, allBytes, new byte[] {0x00}, utf8("third"));

        Consumer consumer = consumer();
        List<byte[]> records = consumer.poll();

        assertEquals(3, records.size());
        assertArrayEquals(allBytes, records.get(0));
        assertEquals(3, consumer.position());
        assertEquals(3, consumer.highWatermark());
        assertEquals(0, consumer.lag());
    }

    /**
     * The steady state of a consumer that is keeping up, and the one it must not read as the end of
     * the log.
     */
    @Test
    @DisplayName("fetching at the high watermark is empty and not an error")
    void fetchingAtTheHighWatermarkIsEmpty() {
        broker.seed("t", 0, utf8("one"));

        Consumer consumer = consumer();
        consumer.poll();

        assertEquals(List.of(), consumer.poll());
        assertEquals(1, consumer.position());
    }

    /**
     * maxBytes cuts on a byte boundary, so a full response normally ends inside a record. Returning
     * the fragment corrupts data; counting it as the end of the log stalls for ever.
     */
    @Test
    @DisplayName("a truncated tail is dropped and re-fetched whole")
    void truncatedTailIsDroppedAndRefetched() {
        broker.seed("t", 0, repeat('A', 100), repeat('B', 100));
        // One whole record is 8 bytes of header and 100 of payload; 150 stops inside the second.
        Consumer consumer = consumer().maxBytes(150);

        List<byte[]> first = consumer.poll();
        assertEquals(1, first.size());
        assertArrayEquals(repeat('A', 100), first.get(0));
        assertEquals(1, consumer.position(), "the partial record must not be counted");

        List<byte[]> second = consumer.poll();
        assertEquals(1, second.size());
        assertArrayEquals(repeat('B', 100), second.get(0));
    }

    /** The other branch: no size field to read, so it is found by having bytes left over. */
    @Test
    @DisplayName("a response stopping inside a record header is truncation")
    void responseStoppingInsideAHeaderIsTruncation() {
        broker.seed("t", 0, repeat('A', 20), repeat('B', 20));
        // 28 bytes is the first record whole, then 4 bytes into the second record's 8-byte header.
        Consumer consumer = consumer().maxBytes(32);

        assertEquals(1, consumer.poll().size());
        assertEquals(1, consumer.position());
    }

    /**
     * The stall that does not resolve itself: every retry makes the identical request, so the
     * consumer keeps running, reports nothing and never advances.
     */
    @Test
    @DisplayName("a record larger than maxBytes is reported rather than retried")
    void recordLargerThanMaxBytesIsReported() {
        broker.seed("t", 0, repeat('A', 500));
        Consumer consumer = consumer().maxBytes(100);

        RecordExceedsMaxBytesException failure =
                assertThrows(RecordExceedsMaxBytesException.class, consumer::poll);

        assertEquals(500, failure.recordBytes());
        assertEquals(100, failure.maxBytes());
        assertEquals(0, failure.offset());
        assertEquals(0, consumer.position(), "the position must not move past an unread record");
    }

    /**
     * The client is the only party that can catch this: on the zero-copy path the broker never
     * touches the record bytes it sends.
     */
    @Test
    @DisplayName("a corrupt record is rejected")
    void corruptRecordIsRejected() {
        broker.seed("t", 0, utf8("payload"));
        broker.corrupt = true;

        CorruptRecordException failure = assertThrows(CorruptRecordException.class, consumer()::poll);

        assertEquals(0, failure.offset());
        assertNotEquals(failure.stored(), failure.computed());
    }

    /**
     * One line apart in the decoder: the length check has to come before the checksum, or every full
     * response is an alarm.
     */
    @Test
    @DisplayName("truncation is not reported as corruption")
    void truncationIsNotCorruption() {
        broker.seed("t", 0, repeat('A', 100), repeat('B', 100));
        assertEquals(1, consumer().maxBytes(150).poll().size());
    }

    /**
     * A record whose checksum has the high bit set.
     *
     * <p>The sum lives in an {@code int} on both sides here, so the bits match either way — but
     * {@code CRC32C.getValue()} returns a {@code long}, and comparing that against the wire's int
     * without the cast is a sign extension that matches nothing. That is this language's version of
     * the trap Python and JavaScript hit at the same line.
     */
    @Test
    @DisplayName("a record whose checksum has the high bit set round-trips")
    void highBitChecksumRoundTrips() {
        byte[] payload = null;
        for (int value = 0; value < 256 && payload == null; value++) {
            byte[] candidate = new byte[] {(byte) value};
            if (Connection.checksum(candidate) < 0) {
                payload = candidate;
            }
        }
        assertTrue(payload != null, "no single byte hashes with the high bit set");

        broker.seed("t", 0, payload);
        assertArrayEquals(payload, consumer().poll().get(0));
    }

    /**
     * Always v2, so the waiting fields are not exercised only in the branch nobody debugs. Asserted
     * from the broker's side, the only place that can tell what was actually sent.
     */
    @Test
    @DisplayName("FETCH goes out as v2 with the waiting fields")
    void fetchGoesOutAsV2() {
        broker.seed("t", 0, utf8("one"));
        connection.fetch("t", 0, 0, 4096, 250, 64);

        assertEquals(2, broker.lastVersion);
        assertArrayEquals(new int[] {0, 0, 4096, 250, 64}, broker.lastFetch);
    }

    @Test
    @DisplayName("a refusal reaches the caller")
    void refusalReachesTheCaller() {
        broker.refuse(Code.OFFSET_OUT_OF_RANGE);
        BrokerException failure = assertThrows(BrokerException.class, consumer()::poll);
        assertEquals(Code.OFFSET_OUT_OF_RANGE, failure.code());
    }

    @Test
    @DisplayName("seek moves the position")
    void seekMovesThePosition() {
        broker.seed("t", 0, utf8("zero"), utf8("one"), utf8("two"));
        Consumer consumer = consumer();
        consumer.seek(2);

        List<byte[]> records = consumer.poll();
        assertEquals(1, records.size());
        assertArrayEquals(utf8("two"), records.get(0));
    }

    @Test
    @DisplayName("iterating yields every record once")
    void iteratingYieldsEveryRecordOnce() {
        List<byte[]> seeded = List.of(utf8("a"), utf8("b"), utf8("c"));
        broker.seed("t", 0, seeded.toArray(byte[][]::new));

        List<byte[]> read = new ArrayList<>();
        Iterator<byte[]> records = consumer().iterator();
        while (read.size() < seeded.size()) {
            read.add(records.next());
        }

        for (int index = 0; index < seeded.size(); index++) {
            assertArrayEquals(seeded.get(index), read.get(index));
        }
    }

    /**
     * The vectors, found by walking up rather than trusting the working directory: Gradle runs tests
     * from this client's directory and an editor may not, and a fixture that resolves in one but not
     * the other gets deleted by whoever hits it second.
     */
    private static List<String[]> readVectors(String name) {
        Path directory = Path.of("").toAbsolutePath();
        while (!Files.exists(directory.resolve("conformance").resolve("vectors").resolve(name))) {
            directory = directory.getParent();
            assertTrue(directory != null, "conformance/vectors/" + name + " not found above the test");
        }

        try {
            List<String[]> rows = new ArrayList<>();
            for (String line :
                    Files.readAllLines(directory.resolve("conformance").resolve("vectors").resolve(name))) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    // -1 keeps trailing empty fields, and the empty-payload vector is exactly the
                    // one a default split drops.
                    rows.add(line.split("\t", -1));
                }
            }
            return rows;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
