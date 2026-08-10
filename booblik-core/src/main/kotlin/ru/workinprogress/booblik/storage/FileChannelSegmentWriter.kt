package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Position
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [SegmentWriter] over a plain `FileChannel` — the way Kafka actually writes its log
 * (`FileRecords.append`, research §1.1).
 *
 * One `write` syscall per record would be the naive shape and it is not what happens here: the
 * length prefix and the payload go out in a single gathering write, because splitting them doubles
 * the syscall count for records that are mostly small.
 *
 * The scratch buffer is a field, not a local: allocating a 4-byte `ByteBuffer` per append is the
 * kind of garbage that does not show up in a microbenchmark of the write itself and does show up
 * as GC pressure at a million records per second.
 */
class FileChannelSegmentWriter(
    private val channel: FileChannel,
    override val capacity: Int,
) : SegmentWriter {
    private val prefix: ByteBuffer = ByteBuffer.allocateDirect(SegmentWriter.LENGTH_PREFIX)
    private val frame = arrayOfNulls<ByteBuffer>(2)

    private var written: Int = channel.size().toInt()

    override val size: Position get() = Position(written)

    override fun append(
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): Position {
        require(hasRoomFor(length)) { "segment full: $written + ${SegmentWriter.LENGTH_PREFIX + length} > $capacity" }
        val start = written

        prefix.clear()
        prefix.putInt(length)
        prefix.flip()

        val body = ByteBuffer.wrap(payload, offset, length)
        frame[0] = prefix
        frame[1] = body

        @Suppress("UNCHECKED_CAST")
        val buffers = frame as Array<ByteBuffer>
        // A gathering write is allowed to write less than everything, and on a regular file it
        // effectively never does — but "effectively never" is how a corrupt segment gets written
        // once a week in production, so the loop stays.
        while (prefix.hasRemaining() || body.hasRemaining()) {
            channel.write(buffers, 0, 2)
        }

        written = start + SegmentWriter.LENGTH_PREFIX + length
        return Position(start)
    }

    override fun force() {
        // `false`: file data only, not the metadata. The file is pre-sized at creation, so its
        // length never changes on the append path and flushing metadata would buy nothing.
        channel.force(false)
    }

    override fun close() {
        channel.close()
    }
}
