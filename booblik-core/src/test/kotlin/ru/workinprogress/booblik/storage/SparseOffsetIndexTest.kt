package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparseOffsetIndexTest {
    @Test
    fun `index is sparse - one entry per interval, not per record`() {
        val index = SparseOffsetIndex(Offset.ZERO, intervalBytes = 1024)
        var position = Position.ZERO
        repeat(1000) { i ->
            index.append(Offset(i.toLong()), position, recordBytes = 64)
            position += 64
        }
        // 1000 records x 64 bytes = 64000 bytes of log; one entry per 1024 bytes plus the first.
        assertEquals(64_000 / 1024 + 1, index.entryCount)
    }

    @Test
    fun `lookup returns the greatest entry at or below the target`() {
        val index = SparseOffsetIndex(Offset(100), intervalBytes = 128)
        var position = Position.ZERO
        repeat(200) { i ->
            index.append(Offset(100L + i), position, recordBytes = 64)
            position += 64
        }

        val found = index.lookup(Offset(150))!!
        assertTrue(found.offset <= Offset(150), "lookup must never overshoot: got ${found.offset}")
        // Entries land every other record here (128 / 64), so the answer for offset 150 is 150.
        assertEquals(Offset(150), found.offset)
        assertEquals(Position(50 * 64), found.position)
    }

    @Test
    fun `lookup below the base offset yields null`() {
        val index = SparseOffsetIndex(Offset(100))
        index.append(Offset(100), Position.ZERO, recordBytes = 8)
        assertNull(index.lookup(Offset(99)))
    }

    @Test
    fun `offsets are stored relative to the base so a far base offset is not truncated`() {
        // A base offset above Int.MAX_VALUE is the case that a naive int-packed index gets wrong,
        // and it only shows up after a few billion records — that is, never in a small test unless
        // the test asks for it directly.
        val base = Offset(5_000_000_000L)
        val index = SparseOffsetIndex(base, intervalBytes = 16)
        index.append(base, Position.ZERO, recordBytes = 32)
        index.append(base + 1, Position(32), recordBytes = 32)

        assertEquals(base + 1, index.lookup(base + 1)!!.offset)
        assertEquals(Position(32), index.lookup(base + 1)!!.position)
    }

    @Test
    fun `a full index stops accepting entries instead of overflowing the array`() {
        val index = SparseOffsetIndex(Offset.ZERO, intervalBytes = 1, maxEntries = 4)
        repeat(10) { i -> index.append(Offset(i.toLong()), Position(i * 8), recordBytes = 8) }
        assertEquals(4, index.entryCount)
        assertTrue(index.isFull)
    }
}
