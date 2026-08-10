package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset

/**
 * An append-only log the writer coroutine owns exclusively.
 *
 * Exists so that [ru.workinprogress.booblik.log.PartitionWriter] does not have to know whether it
 * is writing into a single segment or into a partition that rolls over between segments. The
 * distinction matters to storage and not at all to the actor, and keeping it out of the actor is
 * what lets rollover (M-20) arrive without touching the write path.
 *
 * **Not thread-safe, and not meant to be.** Exactly one coroutine calls these methods. Ordering
 * comes from that ownership rather than from a lock — the reason `booblik-core` contains no
 * `Mutex` and no `synchronized` at all.
 */
interface Log {
    /** Offset the next appended record will get. Safe to read from other threads. */
    val nextOffset: Offset

    /** True when a record of [payloadSize] bytes can still be accepted. */
    fun hasRoomFor(payloadSize: Int): Boolean

    /** Appends one record and returns the offset it got. */
    fun append(
        payload: ByteArray,
        from: Int = 0,
        length: Int = payload.size,
    ): Offset

    /** Makes everything written so far durable. Expensive; see [SegmentWriter.force]. */
    fun force()
}

/**
 * A record whose bytes do not match the checksum stored with them.
 *
 * Thrown by the reading path, which has the bytes and can tell. The zero-copy path cannot and does
 * not try — the client verifies there instead, because it is the one that ends up holding the
 * bytes. Recovery does not throw at all: it stops, keeping everything before the damage, which is
 * the only useful thing to do with a log whose tail is questionable.
 */
class CorruptRecordException(
    baseOffset: ru.workinprogress.booblik.Offset,
    offset: ru.workinprogress.booblik.Offset,
    position: ru.workinprogress.booblik.Position,
) : IllegalStateException("record at offset $offset (segment $baseOffset, position $position) fails its checksum")
