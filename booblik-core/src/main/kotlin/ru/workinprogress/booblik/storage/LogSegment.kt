package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    val file: Path,
    private val writer: SegmentWriter,
    private val readChannel: FileChannel,
    private val index: SparseOffsetIndex,
    recoveredNextOffset: Offset,
) : Log,
    Closeable {
    @Volatile
    override var nextOffset: Offset = recoveredNextOffset
        private set

    private val readers = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    @Volatile
    var retired: Boolean = false
        private set

    /** Bytes of live log in this segment. Readers must not look past this. */
    val size: Position get() = writer.size

    val isFull: Boolean get() = index.isFull

    override fun hasRoomFor(payloadSize: Int): Boolean = writer.hasRoomFor(payloadSize) && !index.isFull

    /** Appends one record and returns the offset it got. */
    override fun append(
        payload: ByteArray,
        from: Int,
        length: Int,
    ): Offset {
        val assigned = nextOffset
        val position = writer.append(payload, from, length)
        index.append(assigned, position, SegmentWriter.RECORD_HEADER + length)
        // Published last: an offset is only visible once its bytes are.
        nextOffset = assigned.inc()
        return assigned
    }

    override fun force() = writer.force()

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
        val header = ByteBuffer.allocate(SegmentWriter.RECORD_HEADER)
        while (current < offset) {
            if (position.value >= limit) return null
            header.clear()
            if (readChannel.read(header, position.value.toLong()) != SegmentWriter.RECORD_HEADER) return null
            header.flip()
            position = position + SegmentWriter.RECORD_HEADER + header.int
            current = current.inc()
        }
        return position
    }

    /** Reads one record into the heap. For tests and for the non-zero-copy path; not the hot path. */
    fun read(offset: Offset): ByteArray? {
        val position = positionOf(offset) ?: return null
        val header = ByteBuffer.allocate(SegmentWriter.RECORD_HEADER)
        if (readChannel.read(header, position.value.toLong()) != SegmentWriter.RECORD_HEADER) return null
        header.flip()
        val length = header.int
        val expectedCrc = header.int
        val body = ByteBuffer.allocate(length)
        var read = 0
        while (read < length) {
            val n = readChannel.read(body, (position.value + SegmentWriter.RECORD_HEADER + read).toLong())
            if (n < 0) return null
            read += n
        }
        val bytes = body.array()
        // Verified here because this path already has the bytes in hand. The zero-copy path cannot
        // do this and does not try — it never touches them, which is the whole point of it.
        if (SegmentWriter.checksum(bytes, 0, length) != expectedCrc) {
            throw CorruptRecordException(baseOffset, offset, position)
        }
        return bytes
    }

    /**
     * Reads raw framed bytes into [buffer] — the same bytes [transferTo] would stream, but through
     * the heap.
     *
     * Exists to be the control in a measurement, not because anything wants it: M-35 compares the
     * zero-copy read path against the ordinary one, and a comparison needs both halves implemented
     * with the same care. If `transferTo` turns out not to be worth its cost, this is what stays.
     */
    fun readInto(
        from: Position,
        buffer: ByteBuffer,
    ): Int = readChannel.read(buffer, from.value.toLong())

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

    /**
     * Drops [offset] and everything after it. Used by recovery for a record that was half-written
     * when the process died, and by benchmarks that recycle a segment instead of remapping it.
     *
     * Order matters and is not interchangeable: `nextOffset` first, so no reader can be pointed at
     * bytes that are about to go away, and only then the bytes themselves.
     */
    fun truncateTo(offset: Offset) {
        require(offset >= baseOffset) { "cannot truncate below the base offset" }
        if (offset >= nextOffset) return
        val position = positionOf(offset) ?: return
        nextOffset = offset
        index.truncateTo(offset)
        writer.truncateTo(position)
    }

    /**
     * Registers a reader. Returns false if the segment has already been retired **and** nobody
     * else is holding it — the caller must then go and look elsewhere.
     *
     * Segments outlive their own deletion on purpose. Retention unlinks the file while readers may
     * still be streaming from it, which on Linux and macOS is safe: the data stays reachable
     * through any descriptor that is already open, and the space comes back when the last one
     * closes. What must not happen is closing those descriptors early, so the count decides when.
     */
    fun acquire(): Boolean {
        if (retired) return false
        readers.incrementAndGet()
        // Checked again after the increment, and the second check is the one that makes this
        // correct: `retire` may have run in between, seen a count of zero and closed the channels.
        // Releasing here re-runs that decision with the count we just published.
        if (retired) {
            release()
            return false
        }
        return true
    }

    /** Releases a reader. Closes the segment if it was retired while this reader held it. */
    fun release() {
        if (readers.decrementAndGet() == 0 && retired) closeNow()
    }

    /**
     * Unlinks the file and stops handing the segment out to new readers. The descriptors stay open
     * until the last current reader is gone — see [acquire].
     */
    fun retire() {
        retired = true
        Files.deleteIfExists(file)
        if (readers.get() == 0) closeNow()
    }

    override fun close() {
        retired = true
        closeNow()
    }

    private fun closeNow() {
        if (closed.compareAndSet(false, true)) {
            writer.close()
            readChannel.close()
        }
    }

    companion object {
        private const val FILE_SUFFIX = ".log"

        const val DEFAULT_CAPACITY: Int = 512 * 1024 * 1024

        /** File name for [baseOffset]: zero-padded to 20 digits, Kafka's convention. */
        fun fileName(baseOffset: Offset): String = "%020d%s".format(baseOffset.value, FILE_SUFFIX)

        /**
         * Opens (creating if needed) the segment file for [baseOffset] under [dir], **recovering**
         * whatever is already in it.
         *
         * The name is the base offset zero-padded to 20 digits, same convention Kafka uses: it makes
         * lexicographic order equal numeric order, so listing the directory is already the segment
         * list in the right order.
         *
         * ## Recovery
         *
         * There is no index file, so the index is rebuilt by walking the record headers — see
         * [recover]. That is a decision with a measured basis rather than a shortcut: M-23.
         *
         * A record whose declared length runs past the end of what was written is a record that was
         * being appended when the process died. It is discarded, and the segment reopens at the last
         * intact boundary. Everything before it is trusted **on the strength of its length prefix
         * alone** — there are no checksums, so a torn write *inside* a record body is not
         * detectable here. That is a known gap, not an oversight; M-60 is where it gets exercised.
         */
        fun open(
            dir: Path,
            baseOffset: Offset,
            mode: SegmentMode = SegmentMode.FILE_CHANNEL,
            capacity: Int = DEFAULT_CAPACITY,
            indexIntervalBytes: Int = SparseOffsetIndex.DEFAULT_INTERVAL_BYTES,
        ): LogSegment {
            dir.createDirectories()
            val file = dir.resolve(fileName(baseOffset))

            val writeChannel =
                FileChannel.open(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            val readChannel = FileChannel.open(file, StandardOpenOption.READ)
            val index = SparseOffsetIndex(baseOffset, indexIntervalBytes)

            // How far the data can possibly reach differs between the two write paths, and getting
            // this wrong is silent: a mapped segment is pre-sized to its full capacity, so its file
            // length says nothing at all about how much log is in it. There the end is marked by a
            // zero length prefix; with a plain FileChannel the file length *is* the end.
            val limit =
                when (mode) {
                    SegmentMode.FILE_CHANNEL -> minOf(readChannel.size(), capacity.toLong()).toInt()
                    SegmentMode.MAPPED -> capacity
                }
            val recovered = recover(readChannel, baseOffset, limit, index)

            val writer =
                when (mode) {
                    SegmentMode.FILE_CHANNEL -> {
                        FileChannelSegmentWriter(writeChannel, capacity, recovered.position.value)
                    }

                    SegmentMode.MAPPED -> {
                        MappedSegmentWriter(writeChannel, capacity, recovered.position.value)
                    }
                }
            // A trailing partial record leaves bytes past the recovered position. They are dropped
            // now rather than left to be read back by the next recovery.
            writer.truncateTo(recovered.position)

            return LogSegment(baseOffset, file, writer, readChannel, index, recovered.nextOffset)
        }

        /**
         * Walks record headers from the start of the segment, filling [index] as it goes.
         *
         * Reads in [RECOVERY_BUFFER] chunks rather than four bytes at a time, and the difference is
         * not marginal. The straightforward version issues one `read` syscall per record; measured
         * on a 256 MiB log of 128-byte records (M-23, `StartupProbe`), that was 1.9 million syscalls
         * and 174 MiB/s — a rate at which a 100 GiB log takes ten minutes to open. The bottleneck
         * was never the disk.
         *
         * The buffer is refilled whenever the next header would cross its end. Bodies are skipped
         * rather than read: recovery only cares where each record ends, never what is in it.
         */
        private fun recover(
            channel: FileChannel,
            baseOffset: Offset,
            limit: Int,
            index: SparseOffsetIndex,
        ): Recovered {
            val buffer = ByteBuffer.allocateDirect(RECOVERY_BUFFER)
            var position = Position.ZERO
            var offset = baseOffset
            var bufferStart = -1L

            while (position.value + SegmentWriter.RECORD_HEADER <= limit) {
                val at = position.value.toLong()
                // Refill when the header we are about to read is not wholly inside the buffer.
                if (bufferStart < 0 || at < bufferStart ||
                    at + SegmentWriter.RECORD_HEADER > bufferStart + buffer.limit()
                ) {
                    buffer.clear()
                    val read = channel.read(buffer, at)
                    if (read < SegmentWriter.RECORD_HEADER) break
                    buffer.flip()
                    bufferStart = at
                }

                val headerAt = (at - bufferStart).toInt()
                val length = buffer.getInt(headerAt)
                // Zero is the end-of-log sentinel in a pre-sized file; a negative length is
                // corruption and is treated the same way — stop and keep what came before.
                if (length <= 0) break
                val end = at + SegmentWriter.RECORD_HEADER + length
                if (end > limit) break
                // And the record has to match its own checksum. Without this the length prefix was
                // the only evidence a record was intact, so a body that was half-written came back
                // as data — risk 5, and the reason M-60 exists.
                if (!verify(
                        channel,
                        buffer,
                        bufferStart,
                        at,
                        length,
                        expected =
                            buffer.getInt(headerAt + SegmentWriter.LENGTH_BYTES),
                    )
                ) {
                    break
                }

                index.append(offset, position, SegmentWriter.RECORD_HEADER + length)
                position = Position(end.toInt())
                offset = offset.inc()
            }
            return Recovered(position, offset)
        }

        /**
         * Checks one record against its stored checksum during recovery.
         *
         * Usually the whole record is already inside the read buffer and this costs a CRC over
         * bytes that were fetched anyway. A record that straddles the buffer boundary is read
         * again on its own — rare by construction, since the buffer is a megabyte and records are
         * not.
         */
        private fun verify(
            channel: FileChannel,
            buffer: ByteBuffer,
            bufferStart: Long,
            at: Long,
            length: Int,
            expected: Int,
        ): Boolean {
            val bodyAt = at + SegmentWriter.RECORD_HEADER
            val body = ByteArray(length)
            val inBuffer = (bodyAt - bufferStart).toInt()
            if (inBuffer >= 0 && inBuffer + length <= buffer.limit()) {
                buffer.get(inBuffer, body)
            } else {
                val direct = ByteBuffer.wrap(body)
                var read = 0
                while (read < length) {
                    val n = channel.read(direct, bodyAt + read)
                    if (n < 0) return false
                    read += n
                }
            }
            return SegmentWriter.checksum(body, 0, length) == expected
        }

        /** 1 MiB of headers-and-bodies per syscall. Direct, so the copy into the JVM never happens. */
        private const val RECOVERY_BUFFER = 1 shl 20

        private class Recovered(
            val position: Position,
            val nextOffset: Offset,
        )
    }
}
