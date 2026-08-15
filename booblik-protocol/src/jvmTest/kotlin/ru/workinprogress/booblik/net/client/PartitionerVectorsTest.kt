package ru.workinprogress.booblik.net.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the partitioner to the **whole** table in `conformance/vectors/`, which was computed by a
 * different implementation in a different language (`conformance/vectors/generate.py`).
 *
 * Agreeing with itself is what a wrong partitioner also does; agreeing with an independent reading
 * of the written specification is the property that matters, and it is the property every client in
 * every language is held to.
 *
 * On the JVM rather than in `commonTest` for one dull reason: common Kotlin cannot open a file. The
 * native targets are covered by [PartitionerTest], which inlines the vectors that catch the most.
 *
 * **If this fails, the code is wrong, not the vectors.** Regenerating them to go green turns a
 * fixture into an echo. The one honest reason to regenerate is adding vectors.
 */
class PartitionerVectorsTest {
    @Test
    fun `Fnv1a agrees with its vectors, hash and fold alike`() {
        assertPartitioner("partitioner-fnv1a.tsv", Partitioner.Fnv1a) { Partitioner.fnv1a32(it) }
    }

    /**
     * The superseded partitioner is held to vectors too, and deliberately so: it stays in the
     * library only to keep reading topics written before 0.3.0, which is a promise about its exact
     * behaviour. A compatibility path nobody checks is a compatibility path that quietly stops
     * being one.
     */
    @Test
    fun `JavaArrayHash still agrees with its vectors`() {
        assertPartitioner("partitioner-javahash.tsv", Partitioner.JavaArrayHash) { it.contentHashCode() }
    }

    private fun assertPartitioner(
        file: String,
        partitioner: Partitioner,
        hash: (ByteArray) -> Int,
    ) {
        val rows = vectors(file)
        assertTrue(rows.isNotEmpty(), "no vectors loaded from $file")

        for (row in rows) {
            val name = row.last()
            val key = row[0].hexToBytes()

            assertEquals(row[1].toUInt(), hash(key).toUInt(), "hash of «$name»")

            for ((column, partitions) in PARTITION_COUNTS.withIndex()) {
                assertEquals(
                    row[2 + column].toInt(),
                    partitioner.partitionFor(key, partitions),
                    "partition of «$name» among $partitions",
                )
            }
        }
    }

    private fun vectors(name: String): List<List<String>> =
        repositoryRoot
            .resolve("conformance/vectors/$name")
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            // No `limit`: Kotlin's `split` keeps empty fields, unlike Java's `String.split`. The
            // empty-key vector is a leading empty field, and it is the one a hand-written parser
            // gets wrong first.
            .map { it.split("\t") }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        /** Order of the fold columns in the vector files, as written by their header. */
        val PARTITION_COUNTS = listOf(1, 2, 3, 4, 16, 64)

        /**
         * Walks up rather than trusting the working directory: Gradle runs tests from the module,
         * an IDE often runs them from the repository root, and a fixture that resolves in one but
         * not the other gets deleted by whoever hits it second.
         */
        val repositoryRoot: File =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "conformance/vectors").isDirectory }
                ?: error("conformance/vectors not found above ${System.getProperty("user.dir")}")
    }
}
