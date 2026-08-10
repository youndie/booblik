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
import kotlin.test.assertNull

/** M-22: what the broker knows after it is restarted. */
class RecoveryTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-recovery")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reopening a segment restores the offset and the index`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                val payloads = (0 until 500).map { i -> "record $i".repeat(i % 5 + 1).toByteArray() }
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    payloads.forEach { segment.append(it) }
                }

                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { reopened ->
                    assertEquals(Offset(500), reopened.nextOffset, "mode=$mode")
                    // Reading offset 499 goes through the rebuilt index: without it the lookup
                    // would start at position zero and the forward scan would still land right,
                    // so the offsets are checked at both ends and in the middle.
                    assertContentEquals(payloads[0], reopened.read(Offset.ZERO), "mode=$mode")
                    assertContentEquals(payloads[250], reopened.read(Offset(250)), "mode=$mode")
                    assertContentEquals(payloads[499], reopened.read(Offset(499)), "mode=$mode")
                    assertNull(reopened.read(Offset(500)), "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `appending after recovery continues the same log`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use {
                    it.append("before".toByteArray())
                }
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { reopened ->
                    assertEquals(Offset(1), reopened.append("after".toByteArray()), "mode=$mode")
                    assertContentEquals("before".toByteArray(), reopened.read(Offset.ZERO), "mode=$mode")
                    assertContentEquals("after".toByteArray(), reopened.read(Offset(1)), "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `a half written record is discarded - FILE_CHANNEL, where the file length bounds it`() {
        // Here the file length *is* the log length, so a header claiming more bytes than the file
        // holds is provably a record that never finished. Recovery stops at the last intact
        // boundary and the next append reuses that offset.
        withDir { dir ->
            LogSegment.open(dir, Offset.ZERO, SegmentMode.FILE_CHANNEL, capacity = 1 shl 20).use {
                it.append("intact".toByteArray())
            }
            writeAt(dir, INTACT_RECORD_END) { it.putInt(9_999) }

            LogSegment.open(dir, Offset.ZERO, SegmentMode.FILE_CHANNEL, capacity = 1 shl 20).use { reopened ->
                assertEquals(Offset(1), reopened.nextOffset, "the torn record is gone")
                assertContentEquals("intact".toByteArray(), reopened.read(Offset.ZERO))
                assertEquals(Offset(1), reopened.append("next".toByteArray()))
            }
        }
    }

    @Test
    fun `a half written record is discarded - MAPPED, where the missing prefix bounds it`() {
        // A mapped segment is pre-sized, so the file length proves nothing and the only marker is a
        // zero length prefix. That is why the writer stores the body first and the prefix last: the
        // crash it has to survive leaves body bytes with no header in front of them, and recovery
        // must treat that as the end of the log rather than as data.
        withDir { dir ->
            LogSegment.open(dir, Offset.ZERO, SegmentMode.MAPPED, capacity = 1 shl 20).use {
                it.append("intact".toByteArray())
            }
            // Body written, prefix never reached — exactly what a crash between the two stores
            // leaves behind.
            writeAt(dir, INTACT_RECORD_END + Int.SIZE_BYTES) { it.put("orphaned body".toByteArray()) }

            LogSegment.open(dir, Offset.ZERO, SegmentMode.MAPPED, capacity = 1 shl 20).use { reopened ->
                assertEquals(Offset(1), reopened.nextOffset, "the headerless body is not a record")
                assertContentEquals("intact".toByteArray(), reopened.read(Offset.ZERO))
                assertEquals(Offset(1), reopened.append("next".toByteArray()))
            }
        }
    }

    @Test
    fun `reopening a partition restores every segment and keeps their order`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                val record = ByteArray(200)
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    repeat(12) { log.append(record) }
                }

                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { reopened ->
                    assertEquals(3, reopened.segmentCount, "mode=$mode")
                    assertEquals(Offset(12), reopened.nextOffset, "mode=$mode")
                    assertEquals(Offset.ZERO, reopened.logStartOffset, "mode=$mode")
                    assertContentEquals(record, reopened.read(Offset(11)), "mode=$mode")
                    // And it keeps growing where it left off rather than starting a fourth segment.
                    assertEquals(Offset(12), reopened.append(record), "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `truncating drops the offsets above the cut and reuses them`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    repeat(100) { segment.append("record $it".toByteArray()) }

                    segment.truncateTo(Offset(40))

                    assertEquals(Offset(40), segment.nextOffset, "mode=$mode")
                    assertNull(segment.read(Offset(40)), "mode=$mode")
                    assertContentEquals("record 39".toByteArray(), segment.read(Offset(39)), "mode=$mode")
                    assertEquals(Offset(40), segment.append("reused".toByteArray()), "mode=$mode")
                    assertContentEquals("reused".toByteArray(), segment.read(Offset(40)), "mode=$mode")
                }
            }
        }
    }

    /** Writes raw bytes straight into the segment file, behind the writer's back. */
    private fun writeAt(
        dir: Path,
        position: Long,
        fill: (ByteBuffer) -> Unit,
    ) {
        val buffer = ByteBuffer.allocate(64)
        fill(buffer)
        buffer.flip()
        FileChannel.open(dir.resolve(LogSegment.fileName(Offset.ZERO)), StandardOpenOption.WRITE).use {
            it.write(buffer, position)
        }
    }

    private companion object {
        /** `[int32 length][6 bytes]` for the single record written by the tests above. */
        const val INTACT_RECORD_END = 4L + 6L
    }
}
