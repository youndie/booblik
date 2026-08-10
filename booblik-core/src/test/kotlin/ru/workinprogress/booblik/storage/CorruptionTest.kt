package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M-60: what a checksum buys.
 *
 * Before it, the length prefix was the only evidence that a record was intact, so a body that was
 * half-written came back as data and nothing anywhere could tell. Risk 5 in the research document
 * was exactly this. These tests damage bytes on purpose and require the damage to be noticed.
 */
class CorruptionTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-corruption")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** Flips one bit in the file, behind the writer's back. */
    private fun flipBit(
        dir: Path,
        at: Long,
    ) {
        val file = dir.resolve(LogSegment.fileName(Offset.ZERO))
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val byte = ByteBuffer.allocate(1)
            channel.read(byte, at)
            byte.flip()
            val flipped = (byte.get().toInt() xor 0x01).toByte()
            channel.write(ByteBuffer.wrap(byteArrayOf(flipped)), at)
        }
    }

    @Test
    fun `a flipped bit in a record body is caught on read`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    segment.append("the quick brown fox".toByteArray())
                    segment.append("jumps over the lazy dog".toByteArray())
                }
                // Somewhere inside the first record's body: header is 8 bytes, so byte 12 is in it.
                flipBit(dir, 12)

                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { reopened ->
                    // Recovery stopped at the damage, so the second record is gone with it. That is
                    // the right answer: past a hole, offsets can no longer be trusted to mean what
                    // they meant.
                    assertEquals(Offset.ZERO, reopened.nextOffset, "mode=$mode: nothing survives the first bad record")
                }
            }
        }
    }

    @Test
    fun `damage after a good prefix keeps the good prefix`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                val payloads = (0 until 10).map { "record number $it".toByteArray() }
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    payloads.forEach { segment.append(it) }
                }
                // Corrupt the body of record 5 and nothing before it.
                val recordSize = SegmentWriter.RECORD_HEADER + payloads[0].size
                flipBit(dir, (5L * recordSize) + SegmentWriter.RECORD_HEADER + 2)

                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { reopened ->
                    assertEquals(Offset(5), reopened.nextOffset, "mode=$mode: five intact records kept")
                    assertContentEquals(payloads[4], reopened.read(Offset(4)), "mode=$mode")
                    // And the log continues from there, reusing the offset the bad record had.
                    assertEquals(Offset(5), reopened.append("after the damage".toByteArray()), "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `reading a corrupt record throws rather than returning wrong bytes`() {
        // Recovery only runs at startup. A segment that is damaged while open — bit rot, a bad
        // sector, somebody else writing to the file — is caught by the reader instead, and the
        // reader must not hand the caller plausible-looking garbage.
        withDir { dir ->
            LogSegment.open(dir, Offset.ZERO, SegmentMode.FILE_CHANNEL, capacity = 1 shl 20).use { segment ->
                segment.append("intact record".toByteArray())
                flipBit(dir, SegmentWriter.RECORD_HEADER + 3L)

                assertFailsWith<CorruptRecordException> { segment.read(Offset.ZERO) }
            }
        }
    }

    @Test
    fun `a torn body is caught even when the length prefix survived`() {
        // The case the mapped path could not detect before M-60, and the reason the checksum was
        // not optional. Prefix-last ordering makes the common crash safe; it does nothing about a
        // record whose header reached the disk and whose body only partly did.
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    segment.append("first".toByteArray())
                    segment.append("second record, longer".toByteArray())
                }
                // Overwrite the second record's body with something else of the same length: the
                // header still says exactly the right number of bytes.
                val secondAt = SegmentWriter.RECORD_HEADER + 5L
                FileChannel
                    .open(dir.resolve(LogSegment.fileName(Offset.ZERO)), StandardOpenOption.WRITE)
                    .use {
                        it.write(
                            ByteBuffer.wrap("SECOND RECORD, LONGER".toByteArray()),
                            secondAt + SegmentWriter.RECORD_HEADER,
                        )
                    }

                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { reopened ->
                    assertEquals(Offset(1), reopened.nextOffset, "mode=$mode: only the first record survives")
                    assertContentEquals("first".toByteArray(), reopened.read(Offset.ZERO), "mode=$mode")
                }
            }
        }
    }
}
