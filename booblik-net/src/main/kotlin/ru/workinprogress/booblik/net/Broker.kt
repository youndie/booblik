package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.FlushPolicy
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.storage.LogSegment
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import ru.workinprogress.booblik.storage.SparseOffsetIndex
import java.io.Closeable
import java.nio.file.Path
import kotlin.io.path.createDirectories

data class BrokerConfig(
    val segmentMode: SegmentMode = SegmentMode.MAPPED,
    val segmentCapacity: Int = LogSegment.DEFAULT_CAPACITY,
    val indexIntervalBytes: Int = SparseOffsetIndex.DEFAULT_INTERVAL_BYTES,
    val flushPolicy: FlushPolicy = FlushPolicy.Disabled,
    /** Total live bytes kept per partition, or null to keep everything. */
    val retainedBytesPerPartition: Long? = null,
)

/**
 * Everything the broker stores: several topics, each cut into partitions, each partition its own
 * log with its own writer.
 *
 * ## What a partition is here
 *
 * A directory, `<topic>-<partition>`, holding that partition's segments — Kafka's layout, and for
 * the same reason: a partition is the unit of ordering and of parallelism, so keeping its files
 * apart is what lets each one have exactly one writer.
 *
 * **Offsets are per partition.** Two partitions of the same topic both start at zero and know
 * nothing about each other. That is not a simplification: an offset that were global would have to
 * be assigned by something both partitions agree with, and the single-writer property — the thing
 * this design is built on — would be gone.
 *
 * ## What is deliberately missing
 *
 * Creating topics. The set of partitions is fixed when the broker opens, and anything else is
 * answered with `UNKNOWN_TOPIC_OR_PARTITION`. A metadata layer means agreeing on cluster state,
 * which is precisely the part of Kafka this project does without.
 */
class Broker private constructor(
    private val handles: Map<PartitionRegistry.Key, PartitionHandle>,
    private val scope: CoroutineScope,
    private val config: BrokerConfig,
) : Closeable {
    val registry = PartitionRegistry(handles)

    /** Every partition this broker serves, in a stable order. */
    val partitions: List<PartitionRegistry.Key> get() = handles.keys.sortedWith(KEY_ORDER)

    fun handle(
        topic: TopicName,
        partition: PartitionId,
    ): PartitionHandle? = registry.find(topic, partition)

    /**
     * Applies the retention policy to every partition. Called by whoever owns the clock — there is
     * no timer in here, because a broker that deletes data on a schedule of its own making is
     * harder to test than one that is told when to.
     */
    fun applyRetention(
        maxAgeMillis: Long? = null,
        nowMillis: Long = 0,
    ): Int {
        var removed = 0
        config.retainedBytesPerPartition?.let { limit ->
            removed += handles.values.sumOf { it.log.retainAtMost(limit) }
        }
        if (maxAgeMillis != null) {
            removed += handles.values.sumOf { it.log.retainNewerThan(maxAgeMillis, nowMillis) }
        }
        return removed
    }

    override fun close() {
        // Writers first: each drains what is already queued, so a batch that was accepted is on
        // disk before the log under it closes. Closing the log first would strand it.
        runBlocking { handles.values.forEach { it.writer.close() } }
        scope.cancel()
        handles.values.forEach { it.log.close() }
    }

    companion object {
        private val KEY_ORDER =
            compareBy<PartitionRegistry.Key>({ it.topic.value }, { it.partition.value })

        /**
         * Opens [partitions] under [dir], recovering whatever is already there.
         *
         * @param partitions topic to partition count, e.g. `mapOf(TopicName("orders") to 4)`
         */
        fun open(
            dir: Path,
            partitions: Map<TopicName, Int>,
            config: BrokerConfig = BrokerConfig(),
        ): Broker {
            require(partitions.isNotEmpty()) { "a broker with no partitions can serve nothing" }
            dir.createDirectories()
            val scope = CoroutineScope(SupervisorJob())

            val handles =
                buildMap {
                    for ((topic, count) in partitions) {
                        require(count > 0) { "topic ${topic.value} needs at least one partition" }
                        for (index in 0 until count) {
                            val partition = PartitionId(index)
                            val log =
                                PartitionLog.open(
                                    dir.resolve(directoryName(topic, partition)),
                                    config.segmentMode,
                                    config.segmentCapacity,
                                    config.indexIntervalBytes,
                                )
                            put(
                                PartitionRegistry.Key(topic, partition),
                                PartitionHandle(
                                    log,
                                    PartitionWriter(
                                        log,
                                        scope,
                                        flushPolicy = config.flushPolicy,
                                    ),
                                ),
                            )
                        }
                    }
                }
            return Broker(handles, scope, config)
        }

        /** `orders-0`. Same shape as Kafka's, and readable in a directory listing. */
        fun directoryName(
            topic: TopicName,
            partition: PartitionId,
        ): String = "${topic.value}-${partition.value}"
    }
}
