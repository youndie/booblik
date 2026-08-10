package ru.workinprogress.booblik.net.client

import kotlinx.coroutines.CompletableDeferred
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.ErrorCode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides which partition a record goes to, from a key the broker never sees.
 *
 * Keys are a **client-side** idea here, and permanently so: the record format has no room for one,
 * so the broker cannot route by key, cannot compact by key, and will never be able to. What it can
 * do is store whatever bytes it is given in whatever partition it is told, which is all a
 * partitioner needs.
 */
fun interface Partitioner {
    fun partitionFor(
        key: ByteArray,
        partitions: Int,
    ): Int

    companion object {
        /**
         * `Arrays.hashCode` over the key, folded into range.
         *
         * Specified by the JDK rather than implementation-defined, so two processes agree — which
         * is the only property a partitioner has to have. `floorMod` and not `%`: the hash is
         * signed, and a negative partition id would fail on the broker for a reason that has
         * nothing to do with the record.
         */
        val ByKeyHash =
            Partitioner { key, partitions ->
                Math.floorMod(key.contentHashCode(), partitions)
            }
    }
}

/**
 * One topic, so its name and its partitions stop being arguments to every call.
 *
 * Obtained from [Producer.topic], which asks the broker what partitions exist (M-70) rather than
 * being told a count. A count passed in by hand is a number that can disagree with the broker, and
 * the failure it produces — records piling into partitions that exist while others are never
 * written to — looks like a data problem rather than a configuration one.
 */
class TopicHandle internal constructor(
    private val producer: Producer,
    val topic: TopicName,
    val partitions: List<PartitionId>,
    private val partitioner: Partitioner = Partitioner.ByKeyHash,
) {
    private val roundRobin = AtomicInteger(0)

    /**
     * Queues [record], choosing a partition from [key].
     *
     * Without a key the partition is taken round-robin. Not at random: round-robin spreads a small
     * number of records evenly, which is what somebody sending a handful of records without a key
     * expects to see, whereas random distribution visibly clumps at small counts and looks like a
     * bug in the partitioner.
     */
    suspend fun send(
        record: ByteArray,
        key: ByteArray? = null,
    ): CompletableDeferred<Offset> = producer.send(topic, partitionFor(key), record)

    fun partitionFor(key: ByteArray?): PartitionId =
        if (key == null) {
            partitions[Math.floorMod(roundRobin.getAndIncrement(), partitions.size)]
        } else {
            partitions[partitioner.partitionFor(key, partitions.size)]
        }
}

/**
 * Records that go to the broker as **one request**.
 *
 * Collected into a list and nothing more: this is not a second accumulator. There is exactly one
 * place in this client where records wait for company — [Producer]'s loop — and adding another
 * would give two places a record can be delayed and two explanations for any latency.
 */
class BatchScope internal constructor() {
    internal val records = ArrayList<ByteArray>()

    fun add(record: ByteArray) {
        records += record
    }

    operator fun ByteArray.unaryPlus() {
        add(this)
    }
}

/**
 * Sends everything [block] adds as a single request and returns the offsets it got.
 *
 * ## What this guarantees, and what it does not
 *
 * The records land **contiguously**: one request is written by one call into the partition's
 * writer, so the offsets are `base`, `base + 1`, and so on with nothing interleaved. That is worth
 * having — it is how a group of records can be found again by knowing where the group started.
 *
 * It is **not atomic**, and reading `batch { }` as a transaction is the mistake this paragraph
 * exists to prevent. A crash mid-write leaves whatever reached the disk; recovery stops at the
 * first record that fails its checksum and keeps everything before it, so a prefix of the batch can
 * survive on its own. There are no transactions in this broker and none are planned.
 *
 * Bypasses the accumulator entirely, because "these records are one request" is the point — an
 * accumulator that merged them with somebody else's records, or split them across two requests when
 * the batch limit fell in the middle, would take the guarantee away.
 */
suspend fun Producer.batch(
    topic: TopicName,
    partition: PartitionId,
    ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    block: BatchScope.() -> Unit,
): List<Offset> {
    val scope = BatchScope().apply(block)
    if (scope.records.isEmpty()) return emptyList()

    val result =
        connection.produce(topic, partition, scope.records, ackPolicy)
            ?: return emptyList() // AckPolicy.NONE: no answer is coming, so there are no offsets.
    if (result.error != ErrorCode.NONE) throw ProduceFailedException(result.error)
    return List(scope.records.size) { result.baseOffset + it.toLong() }
}

/** [batch] against a handle, for the common case of not repeating the topic. */
suspend fun Producer.batch(
    handle: TopicHandle,
    partition: PartitionId,
    ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    block: BatchScope.() -> Unit,
): List<Offset> = batch(handle.topic, partition, ackPolicy, block)
