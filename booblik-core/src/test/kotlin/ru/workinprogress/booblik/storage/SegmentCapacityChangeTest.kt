package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What happens when `segment.capacity.bytes` is lowered under a log that already exists (issue #25).
 *
 * Reported from a running deployment: raising retention meant lowering the capacity, and the restart
 * dropped 1.2 million records and 108 MiB — reported by the broker as a healthy, shorter log. The
 * two startup lines are the only place it showed, and nobody diffs those side by side.
 *
 * The mechanism is in [LogSegment.open]. How far the data can reach was taken from the **configured
 * capacity** rather than from the file — right for a mapped segment, which is pre-sized so its
 * length says nothing about its contents, and wrong the moment the file was written under a
 * different capacity. Recovery then stopped at the new, smaller limit, and the `truncateTo` meant
 * for a torn trailing write planted a zero length prefix at that point: the records past it stayed
 * on disk, unreachable, freeing nothing.
 *
 * Refusing to open is the fix rather than warning. A warning at startup competes with the
 * throughput lines, and by the time anybody reads it the zero has been written.
 */
class SegmentCapacityChangeTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-capacity")
        try {
            return body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** Enough records to run past the capacity the segment is later reopened with. */
    private fun fill(
        dir: Path,
        mode: SegmentMode,
        capacity: Int,
        records: Int,
    ): Offset =
        LogSegment.open(dir, Offset.ZERO, mode, capacity).use { segment ->
            var last = Offset.ZERO
            repeat(records) { last = segment.append(ByteArray(1024) { it.toByte() }) }
            last
        }

    @Test
    fun `a capacity that cannot hold what is already there is refused rather than discarded`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                // 200 records of a kilobyte each: past 64 KiB, inside 1 MiB.
                val last = fill(dir, mode, capacity = 1 shl 20, records = 200)
                assertEquals(Offset(199), last, "mode=$mode")

                val failure =
                    assertFailsWith<IllegalStateException> {
                        LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 16).use {}
                    }

                // The message has to carry the two numbers an operator needs to act: what is on
                // disk and what was asked for. Without them the refusal is only a different way of
                // being unhelpful.
                assertTrue(
                    failure.message!!.contains("65536"),
                    "the refusal should name the configured capacity: ${failure.message}",
                )
                assertTrue(
                    failure.message!!.contains("capacity"),
                    "the refusal should say what the problem is: ${failure.message}",
                )
            }
        }
    }

    @Test
    fun `nothing is lost or written by the refusal itself`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                fill(dir, mode, capacity = 1 shl 20, records = 200)
                val before = Files.size(dir.resolve(LogSegment.fileName(Offset.ZERO)))

                runCatching { LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 16).use {} }

                // The old behaviour truncated on the way through, which is why this is asserted
                // rather than assumed: a refusal that has already damaged the file is not a refusal.
                assertEquals(
                    before,
                    Files.size(dir.resolve(LogSegment.fileName(Offset.ZERO))),
                    "mode=$mode: the file changed size while being refused",
                )

                // And everything is still readable at the capacity it was written with.
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    assertEquals(Offset(200), segment.nextOffset, "mode=$mode")
                }
            }
        }
    }

    @Test
    fun `the same capacity and a larger one still open`() {
        for (mode in SegmentMode.entries) {
            withDir { dir ->
                fill(dir, mode, capacity = 1 shl 20, records = 200)

                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    assertEquals(Offset(200), segment.nextOffset, "mode=$mode: same capacity")
                }
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 21).use { segment ->
                    assertEquals(Offset(200), segment.nextOffset, "mode=$mode: larger capacity")
                }
            }
        }
    }
}
