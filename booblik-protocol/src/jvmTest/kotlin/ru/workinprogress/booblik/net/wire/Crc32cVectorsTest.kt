package ru.workinprogress.booblik.net.wire

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the checksum to the whole table in `conformance/vectors/crc32c.tsv`, computed by an
 * independent implementation in Python.
 *
 * On the JVM rather than in `commonTest` for one dull reason: common Kotlin cannot open a file. What
 * this run checks is therefore the JVM `actual` — the intrinsic — while [Crc32cTest] carries the
 * sharpest vectors inline and runs on both platforms.
 *
 * **If this fails, the code is wrong, not the vectors.**
 */
class Crc32cVectorsTest {
    @Test
    fun `crc32c agrees with its vectors`() {
        val file = findUpwards("conformance/vectors/crc32c.tsv")
        val rows =
            file
                .readLines()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                // Kotlin's split keeps empty fields, which is what the empty-payload vector is.
                .map { it.split("\t") }

        assertTrue(rows.isNotEmpty(), "no vectors loaded")
        rows.forEach { (payloadHex, expected, name) ->
            val payload =
                ByteArray(payloadHex.length / 2) { payloadHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            assertEquals(expected.toUInt().toInt(), crc32c(payload), "vector $name")
        }
    }

    private fun findUpwards(relative: String): File {
        var directory = File(".").absoluteFile
        while (!File(directory, relative).exists()) {
            directory = directory.parentFile ?: error("$relative not found above ${File(".").absolutePath}")
        }
        return File(directory, relative)
    }
}
