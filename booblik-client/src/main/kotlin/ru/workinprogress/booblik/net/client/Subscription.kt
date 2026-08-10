package ru.workinprogress.booblik.net.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.wire.ErrorCode
import java.io.Closeable
import java.net.InetSocketAddress

/**
 * A batch of records and everything needed to carry on after it.
 *
 * The unit is a **batch** and not a record, and that is the one shape decision here worth arguing
 * about. Batching is what this whole project is about — M-14 measured it at 54× against writing one
 * record at a time — so an API that hands out records one by one hides the thing its own numbers
 * are made of. [records] is a list; flattening it is one operator away for anyone who wants that.
 */
data class RecordBatch(
    val topic: TopicName,
    val partition: PartitionId,
    val baseOffset: Offset,
    val records: List<ByteArray>,
    val highWatermark: Offset,
) {
    /**
     * Where to carry on from — the only number a restarting consumer needs.
     *
     * The broker does not store it. That is the same decision that removed the group coordinator,
     * and it is why this is on every batch instead of being something you ask the broker for.
     */
    val nextOffset: Offset get() = baseOffset + records.size.toLong()

    /** Records written but not yet read, as of when this batch was answered. */
    val lag: Long get() = highWatermark.value - nextOffset.value

    val isEmpty: Boolean get() = records.isEmpty()
}

/** Where a subscription starts when it has no stored position of its own. */
sealed interface StartPosition {
    /**
     * The beginning of the **live** log.
     *
     * `logStartOffset`, not zero. On a topic that has ever expired a segment, zero is
     * `OFFSET_OUT_OF_RANGE` — which is why METADATA carries this number at all (M-70).
     */
    data object Earliest : StartPosition

    /** Only what arrives from now on. */
    data object Latest : StartPosition

    data class At(
        val offset: Offset,
    ) : StartPosition
}

data class SubscriptionConfig(
    /** Ceiling on one response. Large enough to be worth a round trip, small enough to hold. */
    val maxBytes: Int = 1 shl 20,
    /**
     * How long the broker may hold a request that has nothing to answer with (M-75).
     *
     * Thirty seconds, deliberately under the idle timeouts NAT boxes and firewalls apply: a held
     * request is a connection with no traffic, and one that outlives its network's patience dies
     * silently on both sides.
     */
    val maxWaitMillis: Int = 30_000,
    /** Answer as soon as anything exists. Raise it to trade latency for fewer, fuller batches. */
    val minBytes: Int = 1,
)

/**
 * Reads topics as a [Flow], one connection per followed partition.
 *
 * ## Why it owns connections instead of taking one
 *
 * A held FETCH occupies its connection until it is answered — a session serves one request at a
 * time, which is the ordering guarantee correlation ids rest on (M-75). Two partitions followed
 * over one connection would therefore take turns: partition B could not be asked until partition
 * A's wait expired. So each followed partition gets its own connection, and that is a property of
 * the protocol rather than a preference about resources.
 *
 * ## Ordering
 *
 * **Within a partition, and only there.** Batches from different partitions interleave in whatever
 * order the brokers answer, because no order between partitions exists in the log either — an API
 * that appeared to provide one would be inventing it.
 *
 * ## Positions
 *
 * Nothing here remembers where you got to. [RecordBatch.nextOffset] is on every batch; storing it
 * is the caller's business, and [OffsetStore] is the shape that plugs in.
 */
class BooblikSubscriber(
    private val address: InetSocketAddress,
    private val config: SubscriptionConfig = SubscriptionConfig(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob())

    /** Which partitions [topic] has, straight from the broker (M-70). */
    suspend fun partitionsOf(topic: TopicName): List<PartitionId> = describe(topic).map { it.partition }

    /**
     * Follows [topic] for ever, waiting on the broker rather than polling it.
     *
     * The flow does not complete on its own. Cancel the collection to stop it; the connections it
     * opened close with it.
     */
    fun follow(
        topic: TopicName,
        from: StartPosition = StartPosition.Latest,
        partitions: List<PartitionId>? = null,
    ): Flow<RecordBatch> = merge(topic, from, partitions, untilCaughtUp = false)

    /**
     * Reads what is already in [topic] and completes.
     *
     * The end is the high watermark **as it was when the flow started**. Measured continuously it
     * would never be reached on a topic anybody is writing to — and "read what is there" is the
     * only reason to call this rather than [follow].
     */
    fun replay(
        topic: TopicName,
        from: StartPosition = StartPosition.Earliest,
        partitions: List<PartitionId>? = null,
    ): Flow<RecordBatch> = merge(topic, from, partitions, untilCaughtUp = true)

    override fun close() {
        scope.cancel()
    }

    private fun merge(
        topic: TopicName,
        from: StartPosition,
        partitions: List<PartitionId>?,
        untilCaughtUp: Boolean,
    ): Flow<RecordBatch> =
        callbackFlow {
            val described = describe(topic).filter { partitions == null || it.partition in partitions }
            check(described.isNotEmpty()) { "no partitions of ${topic.value} to read" }

            // One job per partition, each with its own connection. `SupervisorJob` is not used
            // here on purpose: if one partition's reader dies the subscription is incomplete, and
            // continuing to deliver the others would look like a working subscription that
            // silently skips a third of the topic.
            val readers =
                described.map { info ->
                    launch { readPartition(topic, info, from, untilCaughtUp) { trySend(it) } }
                }
            if (untilCaughtUp) {
                readers.forEach { it.join() }
                close()
            }
            awaitClose { readers.forEach(Job::cancel) }
        }

    private suspend fun readPartition(
        topic: TopicName,
        info: PartitionInfo,
        from: StartPosition,
        untilCaughtUp: Boolean,
        emit: (RecordBatch) -> Unit,
    ) {
        val end = info.highWatermark
        var position =
            when (from) {
                StartPosition.Earliest -> info.logStartOffset
                StartPosition.Latest -> info.highWatermark
                is StartPosition.At -> from.offset
            }

        BooblikConnection(address, scope).use { connection ->
            while (true) {
                if (untilCaughtUp && position >= end) return
                val answer =
                    connection.fetch(
                        topic,
                        info.partition,
                        position,
                        config.maxBytes,
                        // A replay never waits: everything it will ever read already exists, and a
                        // wait would only delay the end of a flow that is about to finish.
                        maxWaitMillis = if (untilCaughtUp) 0 else config.maxWaitMillis,
                        minBytes = config.minBytes,
                    )
                if (answer.error != ErrorCode.NONE) throw FetchFailedException(answer.error)
                if (answer.records.isEmpty()) {
                    // Caught up. Under `follow` the broker already waited before answering, so
                    // there is nothing to sleep off here — asking again immediately is asking it
                    // to wait again.
                    if (untilCaughtUp) return
                    continue
                }
                val batch = RecordBatch(topic, info.partition, position, answer.records, answer.highWatermark)
                emit(batch)
                position = batch.nextOffset
            }
        }
    }

    private suspend fun describe(topic: TopicName): List<PartitionInfo> {
        BooblikConnection(address, scope).use { connection ->
            val answer = connection.metadata(listOf(topic))
            if (answer.error != ErrorCode.NONE) throw FetchFailedException(answer.error)
            return answer.topics
                .singleOrNull()
                ?.partitions
                .orEmpty()
        }
    }
}

/**
 * Where a consumer keeps its position between runs.
 *
 * Declared and not implemented, deliberately. A file, a row in somebody's database, and the
 * transaction the save has to take part in are decisions about a system this library knows nothing
 * about — the same reason there is no metrics exporter here either.
 *
 * The name is `checkpoint` and never `commit`: a commit would imply somebody on the other side took
 * note, and the broker has no idea this happened.
 */
interface OffsetStore {
    suspend fun load(
        topic: TopicName,
        partition: PartitionId,
    ): Offset?

    suspend fun save(
        topic: TopicName,
        partition: PartitionId,
        offset: Offset,
    )
}

/**
 * Saves each batch's [RecordBatch.nextOffset] **after** the collector has handled it.
 *
 * That ordering is the whole semantics: at-least-once. A crash between handling and saving replays
 * the batch. Saving first would be at-most-once — the batch would be skipped instead — and which of
 * the two is acceptable is a property of what the collector does, not of this library, so it is
 * named rather than chosen quietly.
 */
fun Flow<RecordBatch>.checkpointing(store: OffsetStore): Flow<RecordBatch> =
    flow {
        collect { batch ->
            emit(batch)
            store.save(batch.topic, batch.partition, batch.nextOffset)
        }
    }
