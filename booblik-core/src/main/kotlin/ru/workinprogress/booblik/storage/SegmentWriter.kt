package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Position
import java.io.Closeable

/**
 * Append-only sink for one segment file.
 *
 * This interface exists because the draft's premise — "write through `MappedByteBuffer`, the OS
 * flushes" — is a hypothesis, not a given. Kafka, which the draft names as the inspiration, writes
 * its log through a plain `FileChannel` and mmaps only the *index* (research §1.1). Both are
 * therefore implemented and both are benchmarked; the default follows the measurement, not the
 * draft. Nothing above this interface is allowed to know which one it got.
 *
 * Implementations are **not** thread-safe by design: a segment has exactly one writer, and that
 * writer is a single coroutine. Ordering comes from that, not from a lock.
 */
interface SegmentWriter : Closeable {
    /** Bytes already written into this segment. Also the position the next record will land at. */
    val size: Position

    /** Bytes this segment can hold in total. */
    val capacity: Int

    /** True when [payloadSize] plus its length prefix no longer fits. */
    fun hasRoomFor(payloadSize: Int): Boolean = size.value.toLong() + LENGTH_PREFIX + payloadSize <= capacity

    /**
     * Appends one record, framed as `[int32 payloadSize][payload]`, and returns the position the
     * record starts at — the position a reader has to seek to, prefix included.
     */
    fun append(
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size,
    ): Position

    /**
     * Asks the OS to put everything written so far on the physical device.
     *
     * Until this returns, **nothing is durable** — neither implementation buys durability by
     * itself. The mapped one is the more deceptive of the two: writes land in the page cache at
     * memory speed and survive a process crash, so a broker that never calls this looks correct
     * right up to the power loss. Any throughput number must state which flush policy produced it,
     * or it means nothing (docs/benchmarking.md).
     */
    fun force()

    companion object {
        /** Size of the `int32` length prefix each record carries on disk. */
        const val LENGTH_PREFIX = Int.SIZE_BYTES
    }
}
