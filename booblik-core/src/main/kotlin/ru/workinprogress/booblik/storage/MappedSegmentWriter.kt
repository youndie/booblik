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
    private val channel: FileChannel,
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
        require(length > 0) { "a zero-length record is the end-of-log sentinel and cannot be stored" }
        require(hasRoomFor(length)) { "segment full: $written + ${SegmentWriter.RECORD_HEADER + length} > $capacity" }
        val start = written

        // **Body first, length prefix last**, and the order is the whole recovery story for this
        // path. A mapped segment's file is pre-sized to its full capacity, so its length says
        // nothing about how much log is in it; what marks the end is a zero length prefix. Written
        // prefix-first, a crash between the two stores would leave a plausible-looking header in
        // front of a body that was never written, and recovery would hand that garbage back as a
        // record. Written prefix-last, the same crash leaves a zero there and recovery stops
        // exactly where it should.
        //
        // On its own this was not a proof, and it was never read as one: nothing orders the
        // writeback of two pages, so a record straddling a page boundary can still be torn the
        // wrong way round. The checksum below is what closes that gap (M-60) — the ordering here
        // now only decides *where* recovery stops, not whether it can tell.
        MemorySegment.copy(
            MemorySegment.ofArray(payload),
            offset.toLong(),
            segment,
            (start + SegmentWriter.RECORD_HEADER).toLong(),
            length.toLong(),
        )

        // `JAVA_INT_UNALIGNED`, not `JAVA_INT`: the FFM API enforces the layout's alignment and
        // throws `IllegalArgumentException("Target offset N is incompatible with alignment
        // constraint 4")` when a record does not happen to start on a 4-byte boundary — which, with
        // variable-length records, is most of them. `MappedByteBuffer.putInt` never checked, so this
        // is a real difference between the two APIs and not a detail. Found by the tests here.
        //
        // Big-endian on purpose: everything that leaves this process — the wire protocol included —
        // is big-endian, and a segment that disagreed with the wire would need a byte swap on the
        // zero-copy path, which is the one path that must not touch the bytes.
        //
        // Checksum before length, length last. The length is what recovery reads first, so it is
        // the field that must appear last: a record whose length is visible has, by construction,
        // had everything else stored already.
        segment.set(
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN),
            (start + SegmentWriter.LENGTH_BYTES).toLong(),
            SegmentWriter.checksum(payload, offset, length),
        )
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN), start.toLong(), length)

        written = start + SegmentWriter.RECORD_HEADER + length
        return Position(start)
    }

    override fun truncateTo(position: Position) {
        require(position.value <= written) { "truncateTo must not extend the segment: ${position.value} > $written" }
        // The four bytes at the new end are zeroed, and that is the whole operation that makes
        // truncation mean anything here. The file stays pre-sized to `capacity`, so shortening it
        // is not an option; what marks the end of the log is a zero length prefix, and without
        // this store the discarded record's own prefix would still be sitting there for the next
        // recovery to read back as valid.
        if (position.value + SegmentWriter.RECORD_HEADER <= capacity) {
            segment.set(
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN),
                position.value.toLong(),
                0,
            )
        }
        written = position.value
    }

    /**
     * `msync` **and then** `fsync`, and the second call is not belt-and-braces.
     *
     * `MemorySegment.force()` on its own is not a durability barrier. Measured directly (M-24,
     * `DurabilityProbe`): after it returns, an `fsync` on the same file still costs 92 % of what a
     * bare `fsync` costs — so the work `fsync` does was still outstanding. The two calls are not
     * cheap and expensive versions of one thing; they are different things, and only one of them is
     * the promise [SegmentWriter.force] makes.
     *
     * This is why the M0 benchmark's headline — a barrier through a mapping being sixty times
     * cheaper — did not survive contact with the question "cheaper at what". It was measuring a
     * weaker operation on a smaller amount of dirty data.
     */
    override fun force() {
        segment.force()
        channel.force(false)
    }

    override fun close() {
        // Deterministic: the mapping is gone when this returns, not when the GC decides. That is
        // the entire reason for the shared arena.
        arena.close()
        // And the descriptor goes too. Mapping a file does not take ownership of the channel it was
        // mapped from — the mapping outlives it quite happily — so nothing else would ever close
        // this one, and a broker that rolls segments would leak a descriptor per segment.
        channel.close()
    }
}
