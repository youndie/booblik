package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import java.io.Closeable
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/**
 * A partition: an ordered list of segments with exactly one of them open for writing.
 *
 * ## Who touches what
 *
 * Appends come from a single coroutine (see [ru.workinprogress.booblik.log.PartitionWriter]).
 * Reads come from anywhere. The segment list is published as an immutable [List] behind a
 * `@Volatile` field and **replaced** on every change rather than mutated — so a reader either sees
 * the list before a roll or the list after it, never a list being edited. That is why there is no
 * lock here, and why `CopyOnWriteArrayList` is not used: it would take one internally, off the hot
 * path but inside our own code, for a guarantee a volatile reference already gives.
 *
 * ## Reading a segment that retention is deleting
 *
 * [read] and [transferTo] go through [LogSegment.acquire], and retention through
 * [LogSegment.retire]. Retiring unlinks the file immediately and closes the descriptors only once
 * the last reader is gone. On Linux and macOS an unlinked file stays fully readable through any
 * descriptor already open on it, so a consumer streaming a segment when retention catches up keeps
 * getting correct bytes to the end of its request; the space returns when it lets go. New readers
 * never see the segment at all — it left the list first.
 */
class PartitionLog private constructor(
    private val dir: Path,
    private val mode: SegmentMode,
    private val segmentCapacity: Int,
    initial: List<LogSegment>,
) : Log,
    Closeable {
    @Volatile
    private var segments: List<LogSegment> = initial

    /** The one segment that accepts writes. Always the last in the list. */
    val activeSegment: LogSegment get() = segments.last()

    /** Lowest offset still stored. Rises as retention removes segments. */
    val logStartOffset: Offset get() = segments.first().baseOffset

    override val nextOffset: Offset get() = activeSegment.nextOffset

    /** Number of segments, live ones only. */
    val segmentCount: Int get() = segments.size

    /** Bytes on disk across all live segments — the log's own accounting of itself. */
    val sizeInBytes: Long get() = segments.sumOf { it.size.value.toLong() }

    override fun hasRoomFor(payloadSize: Int): Boolean =
        payloadSize.toLong() + SegmentWriter.LENGTH_PREFIX <= segmentCapacity

    /**
     * Appends one record, rolling to a new segment first if the active one cannot take it.
     *
     * A record too large for an empty segment is rejected rather than rolled for: rolling would
     * produce an empty segment and then fail anyway, leaving a stray file behind.
     */
    override fun append(
        payload: ByteArray,
        from: Int,
        length: Int,
    ): Offset {
        require(hasRoomFor(length)) {
            "record of $length bytes never fits a segment of $segmentCapacity"
        }
        if (!activeSegment.hasRoomFor(length)) roll()
        return activeSegment.append(payload, from, length)
    }

    override fun force() = activeSegment.force()

    /** Starts a new active segment at the current end of the log. */
    fun roll(): LogSegment {
        val next = LogSegment.open(dir, activeSegment.nextOffset, mode, segmentCapacity)
        // Replaced, not mutated: a reader holding the old list keeps a consistent view of it.
        segments = segments + next
        return next
    }

    /** The segment that contains [offset], or null if it is outside the log. */
    fun segmentFor(offset: Offset): LogSegment? {
        val snapshot = segments
        if (offset < snapshot.first().baseOffset || offset >= nextOffset) return null
        // Binary search for the last segment whose base offset is at or below the target.
        var low = 0
        var high = snapshot.size - 1
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (snapshot[mid].baseOffset <= offset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return snapshot[found]
    }

    /** Reads one record. Not the hot path — that is [transferTo]. */
    fun read(offset: Offset): ByteArray? {
        val segment = segmentFor(offset) ?: return null
        if (!segment.acquire()) return null
        return try {
            segment.read(offset)
        } finally {
            segment.release()
        }
    }

    /**
     * Streams raw framed bytes starting at [offset] into [target], never crossing a segment
     * boundary — one call, one file, because `transferTo` takes one file.
     *
     * Returns bytes moved, which is routinely less than [maxBytes] and may be zero; see
     * [LogSegment.transferTo].
     */
    fun transferTo(
        offset: Offset,
        maxBytes: Int,
        target: WritableByteChannel,
    ): Long {
        val segment = segmentFor(offset) ?: return 0
        if (!segment.acquire()) return 0
        return try {
            val position = segment.positionOf(offset) ?: return 0
            segment.transferTo(position, maxBytes, target)
        } finally {
            segment.release()
        }
    }

    /**
     * Drops whole segments until the log is at most [maxBytes] long, and never drops the active
     * one. Returns how many went.
     *
     * Retention is per segment, not per record, and that is the point of segments: deleting a
     * prefix of an append-only file is not a thing a filesystem does cheaply, whereas unlinking a
     * whole file is.
     */
    fun retainAtMost(maxBytes: Long): Int {
        var live = segments
        var removed = 0
        while (live.size > 1 && live.sumOf { it.size.value.toLong() } > maxBytes) {
            val oldest = live.first()
            live = live.drop(1)
            segments = live
            // Unlinked only after it is out of the list, so no reader can start on it afterwards.
            oldest.retire()
            removed += 1
        }
        return removed
    }

    /** Drops whole segments whose file has not been modified for [maxAgeMillis]. */
    fun retainNewerThan(
        maxAgeMillis: Long,
        nowMillis: Long,
    ): Int {
        var live = segments
        var removed = 0
        while (live.size > 1) {
            val oldest = live.first()
            val modified = Files.getLastModifiedTime(oldest.file).toMillis()
            if (nowMillis - modified <= maxAgeMillis) break
            live = live.drop(1)
            segments = live
            oldest.retire()
            removed += 1
        }
        return removed
    }

    override fun close() {
        segments.forEach(LogSegment::close)
    }

    companion object {
        /**
         * Opens a partition directory, recovering every segment already in it.
         *
         * Segment files are found by name and sorted by it — the zero-padded naming makes
         * lexicographic order equal numeric order, so the directory listing *is* the segment list.
         * An empty directory gets one segment at offset 0.
         */
        fun open(
            dir: Path,
            mode: SegmentMode = SegmentMode.FILE_CHANNEL,
            segmentCapacity: Int = LogSegment.DEFAULT_CAPACITY,
        ): PartitionLog {
            dir.createDirectories()
            val baseOffsets =
                Files.list(dir).use { stream ->
                    stream
                        .map(Path::name)
                        .filter { it.endsWith(SEGMENT_SUFFIX) && it.length == NAME_LENGTH }
                        .map { Offset(it.removeSuffix(SEGMENT_SUFFIX).toLong()) }
                        .sorted()
                        .toList()
                }

            val segments =
                if (baseOffsets.isEmpty()) {
                    listOf(LogSegment.open(dir, Offset.ZERO, mode, segmentCapacity))
                } else {
                    baseOffsets.map { LogSegment.open(dir, it, mode, segmentCapacity) }
                }
            return PartitionLog(dir, mode, segmentCapacity, segments)
        }

        private const val SEGMENT_SUFFIX = ".log"
        private const val NAME_LENGTH = 20 + 4
    }
}
