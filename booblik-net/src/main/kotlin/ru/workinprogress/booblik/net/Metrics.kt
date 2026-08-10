package ru.workinprogress.booblik.net

import java.util.concurrent.atomic.LongAdder

/**
 * What the broker is doing, in numbers an operator can act on.
 *
 * ## What is here and what is not
 *
 * There is no exporter, no registry and no HTTP endpoint. Shipping metrics somewhere is a decision
 * about somebody's monitoring stack, and this project does not get to make it. What is here is the
 * part that has to live in the broker either way: counters that cost nothing on the hot path, and
 * a [snapshot] that anything can read.
 *
 * ## Why the split between adders and plain fields
 *
 * Network counters are touched by every session coroutine, so they are [LongAdder] — built for
 * exactly this, and it does not allocate per increment. Write-side counters live on
 * [ru.workinprogress.booblik.log.PartitionWriter] as ordinary fields instead, because one coroutine
 * owns them and an atomic there would be paying for contention that cannot happen.
 *
 * ## Consumer lag is not here, and that is not an omission
 *
 * A broker that does not store consumer positions cannot know how far behind anyone is — that is
 * the same decision that removed the group coordinator. What it can report is the end of the log,
 * and a consumer that knows its own position can subtract. Lag belongs to whoever has both numbers,
 * and only the consumer does.
 */
class Metrics {
    private val produceRequests = LongAdder()
    private val fetchRequests = LongAdder()
    private val fetchBytes = LongAdder()
    private val errors = LongAdder()
    private val connectionsOpened = LongAdder()
    private val connectionsClosed = LongAdder()
    private val sessionFailures = LongAdder()
    private val connectionsAccepted = LongAdder()
    private val acceptFailures = LongAdder()
    private val fetchesHeld = LongAdder()
    private val fetchesReleased = LongAdder()

    /**
     * The last thing that went wrong while accepting, kept so that it can be asked about.
     *
     * The accept loop had no error handling at all, and it is a coroutine under a `SupervisorJob`:
     * an exception there killed the broker's ability to accept **any** further connection, silently,
     * while the process stayed up and the port stayed bound. From outside it looks like a broker
     * that answers no one, which is exactly how M-64 presented.
     */
    @Volatile
    var lastAcceptFailure: Throwable? = null
        private set

    /**
     * The last thing that killed a session, kept so that it can be asked about.
     *
     * A session ending is ordinary and gets no entry here: a client that goes away between frames
     * leaves through the normal return path. This is only for the case where a session died
     * *holding a request*, which from the client's side looks like a connection that dropped
     * instead of answering — and which used to be invisible, because the handler caught the
     * exception and discarded it. One intermittent failure in `ServerTest` then had no evidence
     * at all attached to it (M-64).
     */
    @Volatile
    var lastSessionFailure: Throwable? = null
        private set

    fun onProduce() = produceRequests.increment()

    fun onFetch(bytes: Int) {
        fetchRequests.increment()
        fetchBytes.add(bytes.toLong())
    }

    fun onError() = errors.increment()

    /** A session died on an exception rather than on the client leaving. */
    fun onSessionFailure(cause: Throwable) {
        sessionFailures.increment()
        lastSessionFailure = cause
    }

    /**
     * A socket came off the accept queue — before anything is done with it.
     *
     * Separate from [onConnectionOpened] because the gap between the two is real code: the socket
     * is configured and registered with the selector in between, and if it dies there the client
     * sees a connection that was established and then dropped without a word. Counting only
     * sessions made that gap invisible (M-64).
     */
    fun onConnectionAccepted() = connectionsAccepted.increment()

    /**
     * A FETCH started waiting for records that do not exist yet.
     *
     * Paired with [onFetchReleased] so the difference is a gauge of requests parked right now.
     * Without it a healthy broker with a hundred subscribers reads exactly like a dead one —
     * connections up, `produce 0/s`, `fetch 0/s` — and M-64 was a day spent on precisely that kind
     * of indistinguishability.
     */
    fun onFetchHeld() = fetchesHeld.increment()

    fun onFetchReleased() = fetchesReleased.increment()

    /** Accepting a connection failed. The loop survives it; this is how anyone finds out. */
    fun onAcceptFailure(cause: Throwable) {
        acceptFailures.increment()
        lastAcceptFailure = cause
    }

    fun onConnectionOpened() = connectionsOpened.increment()

    fun onConnectionClosed() = connectionsClosed.increment()

    fun snapshot(broker: Broker?): Snapshot =
        Snapshot(
            produceRequests = produceRequests.sum(),
            fetchRequests = fetchRequests.sum(),
            fetchBytes = fetchBytes.sum(),
            errors = errors.sum(),
            sessionFailures = sessionFailures.sum(),
            connectionsAccepted = connectionsAccepted.sum(),
            acceptFailures = acceptFailures.sum(),
            heldFetches = fetchesHeld.sum() - fetchesReleased.sum(),
            connectionsOpened = connectionsOpened.sum(),
            openConnections = connectionsOpened.sum() - connectionsClosed.sum(),
            partitions =
                broker
                    ?.partitions
                    ?.map { key ->
                        val handle = broker.handle(key.topic, key.partition)!!
                        PartitionSnapshot(
                            topic = key.topic.value,
                            partition = key.partition.value,
                            logStartOffset = handle.log.logStartOffset.value,
                            logEndOffset = handle.log.nextOffset.value,
                            segments = handle.log.segmentCount,
                            sizeInBytes = handle.log.sizeInBytes,
                            recordsWritten = handle.writer.recordsWritten,
                            bytesWritten = handle.writer.bytesWritten,
                            flushes = handle.writer.flushes,
                            mailboxDepth = handle.writer.mailboxDepth,
                        )
                    }.orEmpty(),
        )

    data class Snapshot(
        val produceRequests: Long,
        val fetchRequests: Long,
        val fetchBytes: Long,
        val errors: Long,
        /** Sessions that died on an exception. Distinct from [errors], which the client was told about. */
        val sessionFailures: Long,
        /**
         * Every session ever started, cumulative.
         *
         * [openConnections] is a difference and therefore cannot tell "nothing ever connected" from
         * "something connected and left" — both read zero. That ambiguity is exactly what stalled
         * M-64 for a round of guessing.
         */
        val connectionsAccepted: Long,
        /** Failures inside the accept loop. Non-zero means connections were refused by accident. */
        val acceptFailures: Long,
        /** FETCH requests parked right now, waiting for records. Idle subscribers, not a backlog. */
        val heldFetches: Long,
        val connectionsOpened: Long,
        val openConnections: Long,
        val partitions: List<PartitionSnapshot>,
    ) {
        /**
         * Difference between two snapshots, as rates. Counters are cumulative on purpose — a rate
         * is a view, and the two numbers it was computed from should still be there when the view
         * looks wrong.
         */
        fun since(
            previous: Snapshot,
            millis: Long,
        ): String {
            val seconds = millis / 1000.0
            val written = partitions.sumOf { it.recordsWritten } - previous.partitions.sumOf { it.recordsWritten }
            val bytes = partitions.sumOf { it.bytesWritten } - previous.partitions.sumOf { it.bytesWritten }
            val backlog = partitions.sumOf { it.mailboxDepth }
            return (
                "in %.0f rec/s, %.1f MiB/s | produce %.0f/s fetch %.0f/s (%.1f MiB/s) | " +
                    "conns %d backlog %d errors %d dropped %d held %d"
            ).format(
                written / seconds,
                bytes / seconds / 1024 / 1024,
                (produceRequests - previous.produceRequests) / seconds,
                (fetchRequests - previous.fetchRequests) / seconds,
                (fetchBytes - previous.fetchBytes) / seconds / 1024 / 1024,
                openConnections,
                backlog,
                errors,
                sessionFailures,
                heldFetches,
            )
        }
    }

    data class PartitionSnapshot(
        val topic: String,
        val partition: Int,
        val logStartOffset: Long,
        val logEndOffset: Long,
        val segments: Int,
        val sizeInBytes: Long,
        val recordsWritten: Long,
        val bytesWritten: Long,
        val flushes: Long,
        val mailboxDepth: Int,
    )
}
