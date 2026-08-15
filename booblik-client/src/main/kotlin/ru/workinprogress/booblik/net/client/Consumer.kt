package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.wire.ErrorCode

class FetchFailedException(
    val code: ErrorCode,
) : IllegalStateException("broker refused the fetch: $code")

/**
 * The next record is larger than `maxBytes`, so it can never arrive whole.
 *
 * One of the two ways a reader stops making progress, and the only one that does not resolve itself.
 * A response with no whole records and a truncated tail means the next record does not fit; dropping
 * the tail and asking again — which is right in every other case — makes the identical request for
 * ever. The reader keeps running, returns nothing and never advances, and from the outside that is
 * indistinguishable from a reader that has caught up.
 *
 * Thrown rather than retried, because raising `maxBytes` above [recordBytes] is the only fix. Not to
 * be confused with [ErrorCode.RECORD_TOO_LARGE], which is the broker refusing to **store** a record
 * too big for a segment; this is the reader's own limit, chosen by the reader.
 *
 * Added in M-139, after the same place written in Go, Python, Node, .NET and Java all reported it
 * and the JVM client alone went quiet.
 */
class RecordExceedsMaxBytesException(
    val offset: Offset,
    val recordBytes: Int,
    val maxBytes: Int,
) : IllegalStateException(
        "record at offset ${offset.value} needs $recordBytes bytes and maxBytes is $maxBytes, " +
            "so it can never be read whole",
    )

/** A batch of records and where the log ended when they were read. */
data class Records(
    val records: List<ByteArray>,
    val highWatermark: Offset,
) {
    val isEmpty: Boolean get() = records.isEmpty()
}

/**
 * Reads one partition, forward, from wherever it is told to start.
 *
 * **The position lives here, not in the broker.** That is half the reason this project has no
 * consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
 * already knows, and asking a broker to remember it for you is what drags in cluster consensus.
 * The cost is that a restarting consumer must be told where to resume; the benefit is everything
 * that absence removes.
 *
 * Not thread-safe: [position] advances on every [poll]. One consumer, one partition, one caller.
 */
class Consumer(
    private val connection: BooblikConnection,
    private val topic: TopicName,
    private val partition: PartitionId,
    startOffset: Offset = Offset.ZERO,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    /** Offset of the next record this consumer will read. */
    var position: Offset = startOffset
        private set

    /** Moves the read position. Anything already fetched and not returned is simply forgotten. */
    fun seek(offset: Offset) {
        position = offset
    }

    /**
     * Reads the next records and advances [position] past them.
     *
     * An empty result means the consumer has caught up — not an error, and not the end of anything.
     * A caught-up consumer polling at the high watermark is the steady state, so it is answered
     * with an empty batch rather than a failure.
     *
     * A response can end inside a record, because `maxBytes` cuts on a byte boundary. The partial
     * tail is dropped and the position advances only past whole records, so the next poll asks for
     * that record again from its start. The broker will not do this for us: finding the record
     * boundary means parsing the batch, which is the work the zero-copy read path exists to avoid.
     *
     * @throws RecordExceedsMaxBytesException when nothing whole came back and something partial did.
     *     That is the one case where dropping the tail and asking again never terminates, and until
     *     M-139 this method reported it as an empty batch — indistinguishable, from the caller's
     *     side, from having caught up.
     */
    suspend fun poll(): Records {
        val result = connection.fetch(topic, partition, position, maxBytes)
        if (result.error != ErrorCode.NONE) throw FetchFailedException(result.error)
        if (result.records.isEmpty() && result.truncated) {
            throw RecordExceedsMaxBytesException(position, result.truncatedRecordBytes, maxBytes)
        }
        position += result.records.size.toLong()
        return Records(result.records, result.highWatermark)
    }

    /** True when [position] has reached everything the broker had at the last poll. */
    suspend fun isCaughtUp(): Boolean {
        val result = connection.fetch(topic, partition, position, maxBytes = 1)
        if (result.error != ErrorCode.NONE) throw FetchFailedException(result.error)
        return position >= result.highWatermark
    }

    companion object {
        /**
         * 1 MiB. Large enough that a poll is worth its round trip, small enough that a single
         * response cannot dominate a 64 MiB heap on the client side.
         */
        const val DEFAULT_MAX_BYTES = 1024 * 1024
    }
}
