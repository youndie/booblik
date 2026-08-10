package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Position
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * [SegmentWriter] over a memory mapping — the draft's write path.
 *
 * Mapped through the FFM API (`FileChannel.map(mode, offset, size, Arena)`) rather than through
 * `MappedByteBuffer`, and that is not a stylistic preference. `MappedByteBuffer` is `int`-indexed,
 * so it caps a mapping at 2 GB (JDK-6347833), and — the part that actually bites — it has no
 * supported way to release the mapping (JDK-4724038): the region goes away when the GC gets around
 * to the buffer. A broker deletes retained segments on a schedule, so "whenever the GC feels like
 * it" is the wrong lifetime for exactly the object whose lifetime we manage. Kafka works around it
 * with an `Unsafe`-based `ByteBufferUnmapper`; on a JDK 25 toolchain we do not have to.
 * See research §1.5.
 *
 * Mapping a region **pre-sizes the file to the full segment length**. A fresh 1 GiB segment is a
 * 1 GiB file with nothing in it — sparse on ext4/APFS, but `du` and `ls` will disagree, and any
 * disk-usage alarm reads the one that scares people.
 */
class MappedSegmentWriter(
    channel: FileChannel,
    override val capacity: Int,
    initialSize: Int = 0,
) : SegmentWriter {
    private val arena: Arena = Arena.ofShared()
    private val segment: MemorySegment =
        channel.map(FileChannel.MapMode.READ_WRITE, 0, capacity.toLong(), arena)

    private var written: Int = initialSize

    override val size: Position get() = Position(written)

    override fun append(
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): Position {
        require(hasRoomFor(length)) { "segment full: $written + ${SegmentWriter.LENGTH_PREFIX + length} > $capacity" }
        val start = written

        // Two non-obvious choices in one line.
        //
        // `JAVA_INT_UNALIGNED`, not `JAVA_INT`: the FFM API enforces the layout's alignment and
        // throws `IllegalArgumentException("Target offset N is incompatible with alignment
        // constraint 4")` when a record does not happen to start on a 4-byte boundary — which, with
        // variable-length records, is most of them. `MappedByteBuffer.putInt` never checked, so this
        // is a real difference between the two APIs and not a detail. Found by the tests here.
        //
        // Big-endian on purpose: everything that leaves this process — the wire protocol included —
        // is big-endian, and a segment that disagreed with the wire would need a byte swap on the
        // zero-copy path, which is the one path that must not touch the bytes.
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), start.toLong(), length)
        MemorySegment.copy(
            MemorySegment.ofArray(payload),
            offset.toLong(),
            segment,
            (start + SegmentWriter.LENGTH_PREFIX).toLong(),
            length.toLong(),
        )

        written = start + SegmentWriter.LENGTH_PREFIX + length
        return Position(start)
    }

    override fun force() {
        segment.force()
    }

    override fun close() {
        // Deterministic: the mapping is gone when this returns, not when the GC decides. That is
        // the entire reason for the shared arena.
        arena.close()
    }
}
