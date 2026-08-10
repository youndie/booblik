package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Both write paths are run through the same assertions on purpose. The whole reason two of them
 * exist is that we intend to pick one by measurement (research Р1), and that choice is only free
 * if nothing above [SegmentWriter] can tell them apart.
 */
class LogSegmentTest {
    private fun withSegment(
        mode: SegmentMode,
        capacity: Int = 1 shl 20,
        body: (LogSegment) -> Unit,
    ) {
        val dir: Path = Files.createTempDirectory("booblik-segment")
        try {
            LogSegment.open(dir, Offset.ZERO, mode, capacity).use(body)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `append assigns gap-free offsets from the base offset`() {
        for (mode in SegmentMode.entries) {
            withSegment(mode) { segment ->
                val offsets = (0 until 100).map { segment.append("record-$it".toByteArray()) }
                assertEquals((0L until 100L).map(::Offset), offsets, "mode=$mode")
                assertEquals(Offset(100), segment.nextOffset, "mode=$mode")
            }
        }
    }

    @Test
    fun `record read back equals record written`() {
        for (mode in SegmentMode.entries) {
            withSegment(mode) { segment ->
                val payloads = (0 until 500).map { "payload number $it".repeat(it % 7 + 1).toByteArray() }
                payloads.forEach { segment.append(it) }

                payloads.forEachIndexed { i, expected ->
                    assertContentEquals(expected, segment.read(Offset(i.toLong())), "mode=$mode, offset=$i")
                }
            }
        }
    }

    @Test
    fun `reading past the last written offset yields null rather than garbage`() {
        for (mode in SegmentMode.entries) {
            withSegment(mode) { segment ->
                segment.append("only one".toByteArray())
                assertNull(segment.read(Offset(1)), "mode=$mode")
                assertNull(segment.positionOf(Offset(7)), "mode=$mode")
            }
        }
    }

    @Test
    fun `sparse index still lands on the exact record after a forward scan`() {
        // More than one index interval of data, so the lookup genuinely has to scan forward from an
        // entry rather than from position zero.
        for (mode in SegmentMode.entries) {
            withSegment(mode) { segment ->
                val payload = ByteArray(64) { it.toByte() }
                repeat(5_000) { segment.append(payload) }

                assertEquals(Position.ZERO, segment.positionOf(Offset.ZERO), "mode=$mode")
                val recordSize = SegmentWriter.LENGTH_PREFIX + payload.size
                assertEquals(Position(4_321 * recordSize), segment.positionOf(Offset(4_321)), "mode=$mode")
                assertContentEquals(payload, segment.read(Offset(4_999)), "mode=$mode")
            }
        }
    }

    @Test
    fun `transferTo hands over the framed bytes verbatim`() {
        for (mode in SegmentMode.entries) {
            withSegment(mode) { segment ->
                val payload = "zero copy".toByteArray()
                segment.append(payload)

                val sink = ByteArrayOutputStream()
                val target = Channels.newChannel(sink)

                // The loop is the point: a single call is allowed to move less than everything, and
                // the production path against a socket will hit that constantly (research §1.4).
                var moved = 0L
                while (moved < segment.size.value) {
                    val n = segment.transferTo(Position(moved.toInt()), Int.MAX_VALUE, target)
                    if (n == 0L) break
                    moved += n
                }

                val expected =
                    ByteBuffer
                        .allocate(SegmentWriter.LENGTH_PREFIX + payload.size)
                        .putInt(payload.size)
                        .put(payload)
                        .array()
                assertContentEquals(expected, sink.toByteArray(), "mode=$mode")
                assertEquals(expected.size.toLong(), moved, "mode=$mode")
            }
        }
    }

    @Test
    fun `a full segment refuses the append instead of overwriting`() {
        for (mode in SegmentMode.entries) {
            withSegment(mode, capacity = 1024) { segment ->
                val payload = ByteArray(200)
                while (segment.hasRoomFor(payload.size)) segment.append(payload)

                assertTrue(!segment.hasRoomFor(payload.size), "mode=$mode")
                assertEquals(5, segment.nextOffset.value, "mode=$mode: 1024 / (4 + 200) = 5 records")
            }
        }
    }
}
