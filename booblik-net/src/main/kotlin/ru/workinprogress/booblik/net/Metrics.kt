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

    fun onProduce() = produceRequests.increment()

    fun onFetch(bytes: Int) {
        fetchRequests.increment()
        fetchBytes.add(bytes.toLong())
    }

    fun onError() = errors.increment()

    fun onConnectionOpened() = connectionsOpened.increment()

    fun onConnectionClosed() = connectionsClosed.increment()

    fun snapshot(broker: Broker?): Snapshot =
        Snapshot(
            produceRequests = produceRequests.sum(),
            fetchRequests = fetchRequests.sum(),
            fetchBytes = fetchBytes.sum(),
            errors = errors.sum(),
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
                    "conns %d backlog %d errors %d"
            ).format(
                written / seconds,
                bytes / seconds / 1024 / 1024,
                (produceRequests - previous.produceRequests) / seconds,
                (fetchRequests - previous.fetchRequests) / seconds,
                (fetchBytes - previous.fetchBytes) / seconds / 1024 / 1024,
                openConnections,
                backlog,
                errors,
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
