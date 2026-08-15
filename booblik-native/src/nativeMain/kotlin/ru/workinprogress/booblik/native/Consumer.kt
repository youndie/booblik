package ru.workinprogress.booblik.native

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.wire.ErrorCode

/** The broker refused the fetch. */
class FetchFailedException(
    val code: ErrorCode,
) : IllegalStateException("booblik: broker refused the fetch: $code")

/**
 * The next record is larger than [Consumer.maxBytes], so it can never arrive whole.
 *
 * One of the two ways a consumer stalls, and the one that does not resolve itself. A response with
 * no whole records and a truncated tail means the record does not fit; a client that drops the tail
 * and asks again makes exactly the same request for ever — running, reporting nothing, never
 * advancing. Thrown rather than retried, because raising `maxBytes` is the only fix.
 *
 * Not to be confused with [ErrorCode.RECORD_TOO_LARGE], which is the broker refusing to **store** a
 * record too big for a segment. This one is the reader's own limit, chosen by the reader.
 */
class RecordExceedsMaxBytesException(
    val offset: Offset,
    val recordBytes: Int,
    val maxBytes: Int,
) : IllegalStateException(
        "booblik: record at offset ${offset.value} needs $recordBytes bytes and maxBytes is " +
            "$maxBytes, so it can never be read whole",
    )

/**
 * Reads one partition of one topic, forward, from wherever it is told to start.
 *
 * **The position lives here, not in the broker.** That is half the reason this project has no
 * consumer groups, no coordinator and no committed-offset storage: an offset is a number the reader
 * already knows, and asking a broker to remember it is what drags in cluster consensus. The cost is
 * that a restarting consumer has to be told where to resume — [position] is the number to write
 * down, and writing it down *after* the records are dealt with rather than before is what makes a
 * restart re-deliver instead of skip.
 *
 * **Blocking, like the connection under it**, and that is the shape rather than a shortcut. The
 * socket here is a blocking POSIX one; a `Flow` over it would suspend nothing and would only hide
 * which thread is stuck in `recv`. Give the consumer a thread of its own and iterate:
 *
 * ```
 * for (record in consumer.records()) {
 *     handle(record)
 * }
 * ```
 *
 * **Not safe for concurrent use.** Every [poll] advances the position, and the connection matches
 * responses to requests in the order they were sent. One consumer, one partition, one thread.
 */
class Consumer(
    private val connection: BooblikConnection,
    private val topic: TopicName,
    private val partition: PartitionId,
    startOffset: Offset = Offset.ZERO,
    /**
     * How many bytes a response may carry. Larger means fewer round trips and a bigger buffer per
     * fetch; smaller risks [RecordExceedsMaxBytesException] on a large record.
     */
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    /**
     * How long the broker may hold a fetch that has nothing to answer.
     *
     * The default is what keeps a caught-up consumer from becoming a busy loop: without a wait it
     * asks again the instant it is answered, which measurement 24 put at about two thousand
     * pointless requests a second. Waiting costs new records nothing — the broker answers the
     * moment one lands, not when the timer runs out.
     */
    private val maxWaitMillis: Int = DEFAULT_MAX_WAIT_MILLIS,
    /** Holds a response until this much has accumulated, trading latency for round trips. */
    private val minBytes: Int = 0,
) {
    /** Offset of the next record this consumer will read. This is the number to persist. */
    var position: Offset = startOffset
        private set

    /**
     * Where the log ended at the last successful [poll], and zero before the first one. A snapshot
     * rather than a live number: by the time it is read, the log may have grown.
     */
    var highWatermark: Offset = Offset.ZERO
        private set

    /** How many records this consumer was behind at the last poll. Same snapshot caveat. */
    val lag: Long get() = maxOf(0L, highWatermark.value - position.value)

    /** Moves the read position. Anything fetched and not yet returned is simply forgotten. */
    fun seek(offset: Offset) {
        position = offset
    }

    /**
     * Reads the next records and advances [position] past them.
     *
     * **An empty list is not the end of anything.** A consumer that has caught up polls at the high
     * watermark and is answered with no records, which is the steady state of every consumer that is
     * keeping up; treating it as the end of the log is how a consumer stops for ever without
     * erroring.
     *
     * The position advances past whole records only. A response can stop inside a record, because
     * `maxBytes` cuts on a byte boundary; the partial tail is dropped and the next poll asks for
     * that record again from its start. The broker will not do it for us — finding the record
     * boundary means parsing the batch, which is the work the zero-copy read path exists to avoid.
     */
    fun poll(): List<ByteArray> {
        val answer = connection.fetch(topic, partition, position, maxBytes, maxWaitMillis, minBytes)
        if (answer.error != ErrorCode.NONE) throw FetchFailedException(answer.error)

        if (answer.records.isEmpty() && answer.truncated) {
            throw RecordExceedsMaxBytesException(position, answer.truncatedRecordBytes, maxBytes)
        }

        highWatermark = answer.highWatermark
        position += answer.records.size.toLong()
        return answer.records
    }

    /**
     * [poll] one record at a time, fetching again whenever the last batch runs out.
     *
     * **The sequence does not end**: a partition has no end, only a place it has not been written to
     * yet. `break` out of the loop, or close the connection, which ends it with what the socket
     * reports. A `Sequence` and not a `Flow` for the reason the class comment gives — there is
     * nothing here to suspend on.
     *
     * The position advances a whole fetch at a time, not a record at a time. Breaking out mid-batch
     * and persisting [position] skips the rest of that batch, so persist after the loop, or count
     * what was handled.
     */
    fun records(): Sequence<ByteArray> =
        sequence {
            while (true) {
                yieldAll(poll())
            }
        }

    companion object {
        /**
         * 1 MiB: large enough that a fetch is worth its round trip, small enough that one response
         * cannot dominate a small process. Every client in this repository uses the same number.
         */
        const val DEFAULT_MAX_BYTES: Int = 1024 * 1024

        /** Five seconds. See [maxWaitMillis]. */
        const val DEFAULT_MAX_WAIT_MILLIS: Int = 5_000
    }
}
