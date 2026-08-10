package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * M-45: a data directory written by one write path has to open under the other.
 *
 * The two modes produce **identical bytes** — that is the whole premise of having them behind one
 * interface — but they disagree about where the log ends. `FILE_CHANNEL` takes the file length;
 * `MAPPED` pre-sizes the file to its full capacity and takes a zero length prefix instead. Changing
 * the default therefore changes how existing files are read, and that is the part of a default
 * change that bites in production rather than in a benchmark.
 */
class ModeMigrationTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-migration")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private val capacity = 1 shl 20

    @Test
    fun `a log written by FILE_CHANNEL opens under MAPPED`() {
        withDir { dir ->
            val payloads = (0 until 50).map { "written by file channel $it".toByteArray() }
            PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, capacity).use { log ->
                payloads.forEach { log.append(it) }
            }

            PartitionLog.open(dir, SegmentMode.MAPPED, capacity).use { reopened ->
                assertEquals(Offset(50), reopened.nextOffset, "recovery under the new default lost records")
                payloads.forEachIndexed { i, expected ->
                    assertContentEquals(expected, reopened.read(Offset(i.toLong())), "record $i")
                }
                assertEquals(Offset(50), reopened.append("and now mapped".toByteArray()))
            }
        }
    }

    @Test
    fun `a log written by MAPPED opens under FILE_CHANNEL`() {
        // The rollback direction, and the harder one: the mapped writer left the file padded to its
        // full capacity, so `FILE_CHANNEL` sees a file far longer than the log. It must stop at the
        // zero length prefix rather than trusting the size.
        withDir { dir ->
            val payloads = (0 until 50).map { "written by the mapping $it".toByteArray() }
            PartitionLog.open(dir, SegmentMode.MAPPED, capacity).use { log ->
                payloads.forEach { log.append(it) }
            }

            PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, capacity).use { reopened ->
                assertEquals(Offset(50), reopened.nextOffset, "rolling back the default lost records")
                payloads.forEachIndexed { i, expected ->
                    assertContentEquals(expected, reopened.read(Offset(i.toLong())), "record $i")
                }
                assertEquals(Offset(50), reopened.append("back on the channel".toByteArray()))
            }
        }
    }

    @Test
    fun `a mapped segment costs its full capacity on paper from the first record`() {
        // Not a defect, but the operational surprise of the new default: `ls` and any disk-usage
        // alarm read the apparent size. The file is sparse on APFS and ext4, so the space is not
        // actually taken — but nothing an operator looks at says so.
        withDir { dir ->
            LogSegment.open(dir, Offset.ZERO, SegmentMode.MAPPED, capacity).use { segment ->
                segment.append("one small record".toByteArray())
            }
            val file = dir.resolve(LogSegment.fileName(Offset.ZERO))
            assertEquals(capacity.toLong(), file.fileSize(), "a mapped segment is its capacity, not its content")
        }
    }
}
