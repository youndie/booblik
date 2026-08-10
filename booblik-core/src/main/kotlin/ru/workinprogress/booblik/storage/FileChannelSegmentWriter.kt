package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Position
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [SegmentWriter] over a plain `FileChannel` — the way Kafka actually writes its log
 * (`FileRecords.append`, research §1.1).
 *
 * One `write` syscall per record would be the naive shape and it is not what happens here: the
 * header and the payload go out in a single gathering write, because splitting them doubles the
 * syscall count for records that are mostly small.
 *
 * The scratch buffer is a field, not a local: allocating an 8-byte `ByteBuffer` per append is the
 * kind of garbage that does not show up in a microbenchmark of the write itself and does show up
 * as GC pressure at a million records per second.
 */
class FileChannelSegmentWriter(
    private val channel: FileChannel,
    override val capacity: Int,
    initialSize: Int = channel.size().toInt(),
) : SegmentWriter {
    private val header: ByteBuffer = ByteBuffer.allocateDirect(SegmentWriter.RECORD_HEADER)
    private val frame = arrayOfNulls<ByteBuffer>(2)

    private var written: Int = initialSize

    init {
        // The channel's own position has to be moved to match, and forgetting it is silent
        // corruption rather than an error: a gathering write has no positional overload, so it
        // always writes wherever the channel currently is. Reopening a recovered segment left the
        // channel at zero while `written` said otherwise, and the first append after a restart
        // overwrote the beginning of the log. Found by `RecoveryTest`.
        channel.position(written.toLong())
    }

    override val size: Position get() = Position(written)

    override fun append(
        payload: ByteArray,
        offset: Int,
        length: Int,
    ): Position {
        require(length > 0) { "a zero-length record is the end-of-log sentinel and cannot be stored" }
        require(hasRoomFor(length)) { "segment full: $written + ${SegmentWriter.RECORD_HEADER + length} > $capacity" }
        val start = written

        header.clear()
        header.putInt(length)
        header.putInt(SegmentWriter.checksum(payload, offset, length))
        header.flip()

        val body = ByteBuffer.wrap(payload, offset, length)
        frame[0] = header
        frame[1] = body

        @Suppress("UNCHECKED_CAST")
        val buffers = frame as Array<ByteBuffer>
        // A gathering write is allowed to write less than everything, and on a regular file it
        // effectively never does — but "effectively never" is how a corrupt segment gets written
        // once a week in production, so the loop stays.
        while (header.hasRemaining() || body.hasRemaining()) {
            channel.write(buffers, 0, 2)
        }

        written = start + SegmentWriter.RECORD_HEADER + length
        return Position(start)
    }

    override fun truncateTo(position: Position) {
        require(position.value <= written) { "truncateTo must not extend the segment: ${position.value} > $written" }
        // The file is shortened as well as the counter: here the file length *is* the log length,
        // so leaving stale bytes past the end would make the next recovery read them back.
        //
        // Skipped when nothing changes, and not out of thrift: on some JDKs truncating to the
        // current size still touches the modification time, and retention by age reads exactly
        // that timestamp. Recovery calls this on every open, so a segment would get younger every
        // time the broker restarted.
        if (position.value < written) {
            channel.truncate(position.value.toLong())
            written = position.value
        }
        // Always, even when the truncate was skipped: the channel position is where the next
        // gathering write lands, and it must agree with `written` or the log is overwritten.
        channel.position(written.toLong())
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
