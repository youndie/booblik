package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartitionLogTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-partition")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** 204 bytes per record, so a 1024-byte segment holds exactly five. */
    private val record = ByteArray(200) { it.toByte() }

    @Test
    fun `the log rolls to a new segment instead of refusing the write`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    repeat(12) { log.append(record) }

                    assertEquals(Offset(12), log.nextOffset, "mode=$mode")
                    assertEquals(3, log.segmentCount, "mode=$mode: 5 + 5 + 2")
                    assertEquals(
                        listOf(Offset.ZERO, Offset(5), Offset(10)),
                        (0 until 3).map { log.segmentFor(Offset((it * 5).toLong()))!!.baseOffset },
                        "mode=$mode",
                    )
                }
            }
        }
    }

    @Test
    fun `every record is readable across segment boundaries`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    val payloads = (0 until 12).map { i -> ByteArray(200) { (i + it).toByte() } }
                    payloads.forEach { log.append(it) }

                    payloads.forEachIndexed { i, expected ->
                        assertContentEquals(expected, log.read(Offset(i.toLong())), "mode=$mode, offset=$i")
                    }
                    assertNull(log.read(Offset(12)), "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `a record larger than a whole segment is rejected, not rolled for`() {
        // Rolling would create an empty segment and then fail anyway, leaving a stray file behind.
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    val tooBig = ByteArray(2000)
                    val failure = runCatching { log.append(tooBig) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException, "mode=$mode, got $failure")
                    assertEquals(1, log.segmentCount, "mode=$mode: no stray segment")
                }
            }
        }
    }

    @Test
    fun `transferTo does not cross a segment boundary`() {
        // One call, one file — because that is what the syscall underneath takes. A caller that
        // wants more asks again with the offset it reached.
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    repeat(12) { log.append(record) }

                    val sink = ByteArrayOutputStream()
                    val moved = log.transferTo(Offset.ZERO, Int.MAX_VALUE, Channels.newChannel(sink))
                    assertEquals(5L * (SegmentWriter.LENGTH_PREFIX + record.size), moved, "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `retention by size drops whole segments and never the active one`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = 1024).use { log ->
                    repeat(12) { log.append(record) }
                    assertEquals(3, log.segmentCount, "mode=$mode")

                    val removed = log.retainAtMost(maxBytes = 1)
                    assertEquals(2, removed, "mode=$mode")
                    assertEquals(1, log.segmentCount, "mode=$mode: the active segment always stays")
                    assertEquals(Offset(10), log.logStartOffset, "mode=$mode")
                    assertNull(log.read(Offset.ZERO), "mode=$mode: retired data is gone from the log")
                    assertContentEquals(record, log.read(Offset(11)), "mode=$mode")

                    assertEquals(
                        1,
                        dir.listDirectoryEntries("*.log").size,
                        "mode=$mode: retired files are unlinked, not merely forgotten",
                    )
                }
            }
        }
    }

    @Test
    fun `a reader holding a retired segment still reads it to the end`() {
        // The POSIX property this relies on: an unlinked file stays readable through descriptors
        // that were already open. If that ever stopped being true, this test would be the first to
        // notice — the read below would fail rather than return stale-but-correct bytes.
        withDir { dir ->
            PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, segmentCapacity = 1024).use { log ->
                repeat(12) { log.append(record) }
                val oldest = log.segmentFor(Offset.ZERO)!!
                assertTrue(oldest.acquire())

                log.retainAtMost(maxBytes = 1)

                assertTrue(oldest.retired)
                assertContentEquals(record, oldest.read(Offset.ZERO), "the holder still sees the data")
                oldest.release()
            }
        }
    }
}
