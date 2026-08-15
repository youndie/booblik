package ru.workinprogress.booblik.net.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checksum, on whichever platform this test binary was built for.
 *
 * Two `actual`s means two implementations, and the whole risk of `expect`/`actual` is that they
 * quietly disagree — one intrinsic, one table, both plausible. These vectors run on both.
 *
 * The full table from `conformance/vectors/crc32c.tsv` is checked by [Crc32cVectorsTest] on the JVM,
 * common Kotlin having no way to open a file. Inlined here are the ones that catch the most.
 */
class Crc32cTest {
    @Test
    fun `the check value is the Castagnoli one`() {
        // 0xE3069283. zlib's CRC-32 gives 0xCBF43926 for the same input, and nothing about the
        // substitution looks wrong from the outside.
        assertEquals(-0x1CF96D7D, crc32c("123456789".encodeToByteArray()))
    }

    @Test
    fun `an empty payload hashes to zero`() {
        assertEquals(0, crc32c(ByteArray(0)))
    }

    @Test
    fun `high bytes are unsigned`() {
        // 0x80 must enter as 128 rather than as -128, which is the trap every signed-byte language
        // has at this line. From the vectors: crc32c(8081feff) = 698189920.
        assertEquals(698189920, crc32c(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0xFE.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `a single byte hashes as the vectors say`() {
        // crc32c(61) = 3251651376, which does not fit a signed Int — the wire carries the bits and
        // this is what they are as one.
        assertEquals(3251651376u.toInt(), crc32c(byteArrayOf(0x61)))
    }

    @Test
    fun `the sum uses the whole 32 bits`() {
        // A sum with the high bit set comes back negative, and that is correct rather than a bug:
        // the field is four bytes compared for equality. Widening it to a Long is the mistake.
        assertTrue(crc32c(byteArrayOf(0x61)) < 0)
    }
}
