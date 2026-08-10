package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories

/** Which of the two write paths a segment uses. The default follows the benchmark, not taste. */
enum class SegmentMode { FILE_CHANNEL, MAPPED }

/**
 * One segment of a partition log: an append-only data file plus its sparse index.
 *
 * Read and write are deliberately separate objects over the same file. The writer is owned by a
 * single coroutine; readers run concurrently on their own channel and never take a lock. That is
 * safe because the file is append-only — a reader can only ever see a prefix — and because the
 * highest position a reader is allowed to touch is published by the writer through
 * [SegmentWriter.size].
 *
 * The two write paths are coherent with the read channel on Linux and macOS: both have a unified
 * buffer cache, so a mapped store is visible to a subsequent `read()` on another descriptor. This
 * is a property of those kernels, not of the JDK — it is the reason the read path is allowed to be
 * a plain `FileChannel` while the write path may be a mapping.
 */
class LogSegment private constructor(
    val baseOffset: Offset,
    private val writer: SegmentWriter,
    private val readChannel: FileChannel,
    private val index: SparseOffsetIndex,
) : Closeable {
    @Volatile
    var nextOffset: Offset = baseOffset
        private set

    /** Bytes of live log in this segment. Readers must not look past this. */
    val size: Position get() = writer.size

    val isFull: Boolean get() = index.isFull

    fun hasRoomFor(payloadSize: Int): Boolean = writer.hasRoomFor(payloadSize) && !index.isFull

    /** Appends one record and returns the offset it got. */
    fun append(
        payload: ByteArray,
        from: Int = 0,
        length: Int = payload.size,
    ): Offset {
        val assigned = nextOffset
        val position = writer.append(payload, from, length)
        index.append(assigned, position, SegmentWriter.LENGTH_PREFIX + length)
        // Published last: an offset is only visible once its bytes are.
        nextOffset = assigned.inc()
        return assigned
    }

    fun force() = writer.force()

    /**
     * Byte position where [offset] starts, or null if it is not in this segment.
     *
     * The index gets us to within [SparseOffsetIndex.DEFAULT_INTERVAL_BYTES] and the rest is a
     * forward walk over length prefixes. The walk reads 4 bytes at a time and that is fine: those
     * bytes are in the page cache by construction — we just wrote them, or the reader is behind and
     * the kernel has read ahead.
     */
    fun positionOf(offset: Offset): Position? {
        if (offset < baseOffset || offset >= nextOffset) return null
        // No index entry at or below the target means the target is in the first interval: scan
        // from the start of the segment, which is exactly where baseOffset lives.
        val entry = index.lookup(offset) ?: SparseOffsetIndex.IndexEntry(baseOffset, Position.ZERO)
        var current = entry.offset
        var position = entry.position
        val limit = writer.size.value
        val prefix = ByteBuffer.allocate(SegmentWriter.LENGTH_PREFIX)
        while (current < offset) {
            if (position.value >= limit) return null
            prefix.clear()
            if (readChannel.read(prefix, position.value.toLong()) != SegmentWriter.LENGTH_PREFIX) return null
            prefix.flip()
            position = position + SegmentWriter.LENGTH_PREFIX + prefix.int
            current = current.inc()
        }
        return position
    }

    /** Reads one record into the heap. For tests and for the non-zero-copy path; not the hot path. */
    fun read(offset: Offset): ByteArray? {
        val position = positionOf(offset) ?: return null
        val prefix = ByteBuffer.allocate(SegmentWriter.LENGTH_PREFIX)
        if (readChannel.read(prefix, position.value.toLong()) != SegmentWriter.LENGTH_PREFIX) return null
        prefix.flip()
        val length = prefix.int
        val body = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = readChannel.read(body, (position.value + SegmentWriter.LENGTH_PREFIX + read).toLong())
            if (n < 0) return null
            read += n
        }
        return body.array()
    }

    /**
     * Streams raw framed bytes from [from] to [target] without touching the JVM heap — `sendfile`
     * on Linux and macOS when [target] is a socket.
     *
     * Returns the number of bytes actually handed over, which is **routinely less than asked**: a
     * single call is clamped to `Int.MAX_VALUE` by the JDK and to `2^31-1` by `sendfile` itself, and
     * against a non-blocking socket with a full send buffer it legitimately returns 0. The caller
     * loops on readiness; it must not spin (research §1.4).
     */
    fun transferTo(
        from: Position,
        maxBytes: Int,
        target: WritableByteChannel,
    ): Long {
        val available = writer.size.value - from.value
        if (available <= 0) return 0
        val count = minOf(maxBytes.toLong(), available.toLong())
        return readChannel.transferTo(from.value.toLong(), count, target)
    }

    override fun close() {
        writer.close()
        readChannel.close()
    }

    companion object {
        private const val FILE_SUFFIX = ".log"

        const val DEFAULT_CAPACITY: Int = 512 * 1024 * 1024

        /**
         * Opens (creating if needed) the segment file for [baseOffset] under [dir].
         *
         * The name is the base offset zero-padded to 20 digits, same convention Kafka uses: it makes
         * lexicographic order equal numeric order, so listing the directory is already the segment
         * list in the right order.
         */
        fun open(
            dir: Path,
            baseOffset: Offset,
            mode: SegmentMode = SegmentMode.FILE_CHANNEL,
            capacity: Int = DEFAULT_CAPACITY,
        ): LogSegment {
            dir.createDirectories()
            val file = dir.resolve("%020d%s".format(baseOffset.value, FILE_SUFFIX))

            val writeChannel =
                FileChannel.open(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            val writer =
                when (mode) {
                    SegmentMode.FILE_CHANNEL -> FileChannelSegmentWriter(writeChannel, capacity)
                    SegmentMode.MAPPED -> MappedSegmentWriter(writeChannel, capacity)
                }
            val readChannel = FileChannel.open(file, StandardOpenOption.READ)
            return LogSegment(baseOffset, writer, readChannel, SparseOffsetIndex(baseOffset))
        }
    }
}
