package ru.workinprogress.booblik.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Holds this client to the golden vectors, computed by another implementation in another language.
 *
 * <p>Agreeing with itself is what a wrong partitioner also does; agreeing with an independent
 * reading of the written specification is the property that matters. <b>If this fails, this code is
 * wrong — not the vectors.</b>
 */
class PartitionerTest {

    /** The fold columns, in the order the vector file's header gives them. */
    private static final int[] PARTITION_COUNTS = {1, 2, 3, 4, 16, 64};

    @Test
    void matchesTheGoldenVectors() throws IOException {
        List<String[]> rows = vectors("partitioner-fnv1a.tsv");
        assertTrue(!rows.isEmpty(), "no vectors loaded");

        for (String[] row : rows) {
            String name = row[row.length - 1];
            byte[] key = decodeHex(row[0]);

            assertEquals(
                    Long.parseUnsignedLong(row[1]),
                    Integer.toUnsignedLong(Partitioner.fnv1a32(key)),
                    "hash of «" + name + "»");

            for (int index = 0; index < PARTITION_COUNTS.length; index++) {
                assertEquals(
                        Integer.parseInt(row[2 + index]),
                        Partitioner.partitionFor(key, PARTITION_COUNTS[index]),
                        "partition of «" + name + "» among " + PARTITION_COUNTS[index]);
            }
        }
    }

    /**
     * Java's {@code byte} is signed, so 0x80 would sign-extend to 0xFFFFFF80 without the mask. It is
     * the same line Kotlin needs a mask on and JavaScript needs {@code Math.imul} for, and this
     * asserts the property directly rather than trusting the loop to keep it.
     */
    @Test
    void highBytesAreUnsigned() {
        int signExtended = (Partitioner.FNV_OFFSET_BASIS ^ 0xFFFFFF80) * Partitioner.FNV_PRIME;
        assertNotEquals(signExtended, Partitioner.fnv1a32(new byte[] {(byte) 0x80}));
    }

    @Test
    void noPartitionsIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Partitioner.partitionFor("k".getBytes(StandardCharsets.UTF_8), 0));
    }

    private static List<String[]> vectors(String name) throws IOException {
        Path path = findUpwards(Path.of("conformance", "vectors", name));
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // -1 keeps the leading empty field of the empty-key vector, which `split` drops by
            // default. That vector is the one a hand-written parser gets wrong first.
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    /**
     * Walks up rather than trusting the working directory: Gradle runs tests from the project and an
     * IDE may not, and a fixture that resolves in one but not the other gets deleted by whoever hits
     * it second.
     */
    private static Path findUpwards(Path relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(relative + " not found above " + Path.of("").toAbsolutePath());
    }

    private static byte[] decodeHex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
        }
        return out;
    }
}
