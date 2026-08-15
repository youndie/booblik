package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.storage.SegmentWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the checksum to the golden vectors in `conformance/vectors/`, computed by a different
 * implementation in a different language.
 *
 * The partitioner half of this test moved to `:booblik-protocol` in M-134, following the algorithm
 * itself. The checksum stayed, and not by accident: it lives in `:booblik-core`, which is the JVM
 * storage module, because `CRC32C` there is an intrinsic compiling to one instruction. That is the
 * one objection to multiplatform from decision Р8 still standing — and it applies to the consumer
 * alone, which is why a native **publisher** pays nothing for it.
 *
 * CRC-32C has a published check value for `123456789`, so a table built from the wrong polynomial
 * fails here rather than in somebody's client six months from now. Worth asserting on the JVM too:
 * the implementation is intrinsified, and this is the only place that says out loud which of the two
 * CRC-32 variants that instruction computes.
 */
class ConformanceVectorsTest {
    @Test
    fun `the checksum agrees with its vectors`() {
        val rows = vectors("crc32c.tsv")
        assertTrue(rows.isNotEmpty(), "no vectors loaded")

        for (row in rows) {
            val payload = row[0].hexToBytes()
            assertEquals(
                row[1].toUInt(),
                SegmentWriter.checksum(payload, 0, payload.size).toUInt(),
                "CRC32C of «${row.last()}»",
            )
        }
    }

    private fun vectors(name: String): List<List<String>> =
        repositoryRoot
            .resolve("conformance/vectors/$name")
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { it.split("\t") }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        val repositoryRoot: File =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .firstOrNull { File(it, "conformance/vectors").isDirectory }
                ?: error("conformance/vectors not found above ${System.getProperty("user.dir")}")
    }
}
