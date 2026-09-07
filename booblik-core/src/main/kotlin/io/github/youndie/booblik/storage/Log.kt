package io.github.youndie.booblik.storage

import io.github.youndie.booblik.Offset

/**
 * An append-only log the writer coroutine owns exclusively.
 *
 * Exists so that [io.github.youndie.booblik.log.PartitionWriter] does not have to know whether it
 * is writing into a single segment or into a partition that rolls over between segments. The
 * distinction matters to storage and not at all to the actor, and keeping it out of the actor is
 * what lets rollover (M-20) arrive without touching the write path.
 *
 * **Not thread-safe, and not meant to be.** Exactly one coroutine calls these methods. Ordering
 * comes from that ownership rather than from a lock — the reason `booblik-core` contains no
 * `Mutex` and no `synchronized` at all.
 */
public interface Log {
    /** Offset the next appended record will get. Safe to read from other threads. */
    public val nextOffset: Offset

    /** True when a record of [payloadSize] bytes can still be accepted. */
    public fun hasRoomFor(payloadSize: Int): Boolean

    /** Appends one record and returns the offset it got. */
    public fun append(
        payload: ByteArray,
        from: Int = 0,
        length: Int = payload.size,
    ): Offset

    /** Makes everything written so far durable. Expensive; see [SegmentWriter.force]. */
    public fun force()
}

/**
 * A record whose bytes do not match the checksum stored with them.
 *
 * Thrown by the reading path, which has the bytes and can tell. The zero-copy path cannot and does
 * not try — the client verifies there instead, because it is the one that ends up holding the
 * bytes. Recovery does not throw at all: it stops, keeping everything before the damage, which is
 * the only useful thing to do with a log whose tail is questionable.
 */
public class CorruptRecordException(
    baseOffset: io.github.youndie.booblik.Offset,
    offset: io.github.youndie.booblik.Offset,
    position: io.github.youndie.booblik.Position,
) : IllegalStateException("record at offset $offset (segment $baseOffset, position $position) fails its checksum")
