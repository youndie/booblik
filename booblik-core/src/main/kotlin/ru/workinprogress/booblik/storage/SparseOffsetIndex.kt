package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position

/**
 * Offset → position index for one segment. Sparse: one entry per [intervalBytes] of log, not one
 * entry per record. A lookup lands *before or at* the wanted offset and the reader scans forward
 * from there.
 *
 * Two deliberate departures from the draft, both measured in bytes rather than in taste:
 *
 * 1. **Not a `ConcurrentSkipListMap<Offset, Position>`.** A value class in a generic position is
 *    boxed, so that map would allocate a boxed key and a boxed value **per record** — in the one
 *    place the draft's §2 promises there are no allocations. Here an entry is a primitive `Long`
 *    in a `LongArray`: relative offset in the high 32 bits, position in the low 32.
 * 2. **Sparse, not dense.** A dense index costs 8 bytes per record; at small records that is a
 *    sizeable fraction of the data itself. Kafka's default interval is 4 KiB, and the forward scan
 *    it implies is a page-cache walk, not a disk seek.
 *
 * Offsets are stored **relative to [baseOffset]** so an entry fits in 8 bytes rather than 12.
 *
 * Not thread-safe for writes; [append] is called by the same single writer coroutine that owns the
 * segment. Reads run concurrently with writes and see a consistent prefix: [entryCount] is written
 * only after the slot it counts is filled, and it is `@Volatile` for exactly that reason.
 */
class SparseOffsetIndex(
    val baseOffset: Offset,
    private val intervalBytes: Int = DEFAULT_INTERVAL_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    /**
     * Grown on demand rather than allocated at [maxEntries], and replaced wholesale when it grows.
     *
     * The array used to be allocated at full size for every segment — a megabyte of index whether
     * the segment held half a gigabyte or a kilobyte. A broker with a few hundred small segments
     * spent hundreds of megabytes on entries that did not exist, and at a 64 MiB heap it died.
     * Found by `LogPropertiesTest`, which opens many tiny segments on purpose.
     *
     * Sizing it from the segment capacity instead was the obvious fix and the wrong one: records
     * can be larger than the index interval, so `capacity / interval` is not an upper bound on the
     * entries a segment will want, and a too-small index made the **index** the thing that filled
     * up first — segments started rolling after one record.
     */
    @Volatile
    private var entries = LongArray(INITIAL_ENTRIES)

    @Volatile
    private var count: Int = 0

    private var bytesSinceLastEntry: Int = 0

    val entryCount: Int get() = count

    /** Only ever true at the hard cap, which a segment reaches long after it runs out of bytes. */
    val isFull: Boolean get() = count == maxEntries

    /**
     * Offers a record to the index. Most calls do nothing — an entry is only added once
     * [intervalBytes] of log went by since the previous one. Returns true if an entry was added.
     */
    fun append(
        offset: Offset,
        position: Position,
        recordBytes: Int,
    ): Boolean {
        val relative = offset - baseOffset
        require(relative >= 0) { "offset $offset is below base $baseOffset" }
        require(relative <= Int.MAX_VALUE) { "segment spans more than Int.MAX_VALUE offsets" }

        val first = count == 0
        if (!first && bytesSinceLastEntry < intervalBytes) {
            bytesSinceLastEntry += recordBytes
            return false
        }
        if (isFull) return false
        if (count == entries.size) grow()

        entries[count] = (relative shl Integer.SIZE) or (position.value.toLong() and LOW_MASK)
        // Publish the slot before publishing its existence: a reader that sees count == n must see
        // n filled slots. Written second, and volatile, so the store cannot float above the fill.
        count += 1
        bytesSinceLastEntry = recordBytes
        return true
    }

    private fun grow() {
        // Replaced, not resized in place: a reader may be walking the old array, and it stays a
        // consistent view of the entries it was published with. Readers take `count` **before**
        // `entries` so they can never see a count that belongs to an array they have not got.
        entries = entries.copyOf((entries.size * 2).coerceAtMost(maxEntries))
    }

    /**
     * Drops every entry at or above [offset], so the index stops describing records the segment no
     * longer has. Called by recovery after a partial trailing record is discarded, and by a
     * benchmark recycling a segment.
     */
    fun truncateTo(offset: Offset) {
        val relative = offset - baseOffset
        var keep = 0
        while (keep < count && relativeOffsetAt(keep) < relative) keep += 1
        count = keep
        // Reset rather than recompute: the exact byte distance to the next entry is not worth
        // reconstructing, and starting the interval afresh costs at most one extra entry.
        bytesSinceLastEntry = 0
    }

    /**
     * Largest indexed entry whose offset is `<= target`, or null if the index has nothing at or
     * below it. The reader starts a forward scan from the returned position.
     */
    fun lookup(target: Offset): IndexEntry? {
        val relative = target - baseOffset
        if (relative < 0) return null

        // Order matters: `count` first, then the array. The array is then at least as new as the
        // count, so it certainly holds that many entries. Reading them the other way round could
        // pair a fresh count with a stale, shorter array.
        val snapshot = count
        val entries = this.entries
        if (snapshot == 0) return null

        // Binary search for the rightmost entry with relativeOffset <= relative.
        var low = 0
        var high = snapshot - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (entries[mid] ushr Integer.SIZE <= relative) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null

        return IndexEntry(
            offset = baseOffset + (entries[found] ushr Integer.SIZE),
            position = Position((entries[found] and LOW_MASK).toInt()),
        )
    }

    private fun relativeOffsetAt(i: Int): Long = entries[i] ushr Integer.SIZE

    data class IndexEntry(
        val offset: Offset,
        val position: Position,
    )

    companion object {
        /** Same default Kafka uses for `index.interval.bytes`, and for the same reason. */
        const val DEFAULT_INTERVAL_BYTES = 4 * 1024

        /** 128 Ki entries × 4 KiB interval covers a 512 MiB segment with 1 MiB of index. */
        const val DEFAULT_MAX_ENTRIES = 128 * 1024

        /** 64 entries, 512 bytes. A segment that never grows past this costs almost nothing. */
        private const val INITIAL_ENTRIES = 64

        private const val LOW_MASK = 0xFFFF_FFFFL
    }
}
