package ru.workinprogress.booblik.native

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.client.Partitioner
import ru.workinprogress.booblik.net.wire.FetchResponse
import ru.workinprogress.booblik.net.wire.MetadataResult
import ru.workinprogress.booblik.net.wire.ProduceResult
import ru.workinprogress.booblik.net.wire.Protocol
import ru.workinprogress.booblik.net.wire.RequestEncoder
import ru.workinprogress.booblik.net.wire.ResponseDecoder

/** The socket died, or said something that is not a frame. */
class ConnectionException(
    message: String,
) : IllegalStateException("booblik: $message")

/**
 * One connection to a broker, over a blocking POSIX socket.
 *
 * **Not safe for concurrent use.** Requests and responses are matched by a correlation id in the
 * order they were sent, so two callers sharing a connection would read each other's answers.
 *
 * Blocking and synchronous on purpose. A publisher's whole job here is one round trip per batch,
 * and wrapping that in a coroutine dispatcher on Native would buy an abstraction while adding a
 * dependency and a threading model to argue about — see the module's build file.
 */
class BooblikConnection(
    address: String,
) : AutoCloseable {
    private val socket = Socket.connect(address)
    private var correlationId = 0

    /**
     * Appends records to one partition as a single request.
     *
     * They land **contiguously** — one request is written by one call into that partition's writer,
     * so the offsets run from `baseOffset` with nothing interleaved. It is **not** atomic: a crash
     * mid-write leaves whatever reached the disk, and recovery keeps the prefix that passes its
     * checksums.
     *
     * Returns null under [AckPolicy.NONE], because no answer is coming — not an empty response,
     * nothing, since no offset exists until the writer reaches the batch.
     *
     * Nothing here validates record sizes. The broker refuses empty records and empty batches with
     * `CORRUPT_REQUEST`, and duplicating that rule client-side would create a second place to
     * disagree with it.
     */
    fun produce(
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    ): ProduceResult? {
        val id = ++correlationId
        socket.writeFully(RequestEncoder.produce(id, topic, partition, records, ackPolicy))
        if (ackPolicy == AckPolicy.NONE) return null

        val result = ResponseDecoder.produce(readFrame())
        check(result.correlationId == id) {
            "response ${result.correlationId} answered request $id"
        }
        return result
    }

    /**
     * Which topics exist and where each partition currently begins and ends.
     *
     * An empty list asks for everything. A named topic that does not exist fails the whole request
     * with `UNKNOWN_TOPIC_OR_PARTITION` rather than being left out — otherwise "no such topic" and
     * "the topic is empty" would arrive looking identical.
     */
    fun metadata(topics: List<TopicName> = emptyList()): MetadataResult {
        val id = ++correlationId
        socket.writeFully(RequestEncoder.metadata(id, topics))

        val result = ResponseDecoder.metadata(readFrame())
        check(result.correlationId == id) {
            "response ${result.correlationId} answered request $id"
        }
        return result
    }

    /**
     * Reads records from one partition, checksum-verified.
     *
     * [maxBytes] bounds the response **in bytes, not in records**, so it can stop inside one; see
     * [FetchResponse.truncated]. [maxWaitMillis] is how long the broker may hold a request that has
     * nothing to answer with, and zero — the default here, though not in [Consumer] — returns at
     * once.
     *
     * [minBytes] greater than [maxBytes] is `CORRUPT_REQUEST`: a request that can never be
     * satisfied. That is left to the broker rather than checked here, so there is one place that
     * decides it instead of two that can disagree.
     *
     * The request always goes out as v2, including when nothing is being waited for — one code path,
     * rather than a v1 branch that only the caller who never waits would exercise.
     */
    fun fetch(
        topic: TopicName,
        partition: PartitionId,
        offset: Offset,
        maxBytes: Int = Consumer.DEFAULT_MAX_BYTES,
        maxWaitMillis: Int = 0,
        minBytes: Int = 0,
    ): FetchResponse {
        val id = ++correlationId
        socket.writeFully(
            RequestEncoder.fetch(id, topic, partition, offset, maxBytes, maxWaitMillis, minBytes),
        )

        val result = ResponseDecoder.fetch(readFrame(), offset)
        check(result.correlationId == id) {
            "response ${result.correlationId} answered request $id"
        }
        return result
    }

    /**
     * A [Consumer] reading this partition from [start].
     *
     * Reading "from the beginning" means starting at the partition's `logStartOffset` from
     * [metadata], not at zero: zero is `OFFSET_OUT_OF_RANGE` on any topic that has ever dropped a
     * segment to retention. Reading "only what is new" means its `highWatermark`.
     */
    fun consumer(
        topic: TopicName,
        partition: PartitionId,
        start: Offset = Offset.ZERO,
        maxBytes: Int = Consumer.DEFAULT_MAX_BYTES,
        maxWaitMillis: Int = Consumer.DEFAULT_MAX_WAIT_MILLIS,
        minBytes: Int = 0,
    ): Consumer = Consumer(this, topic, partition, start, maxBytes, maxWaitMillis, minBytes)

    /**
     * A handle with the topic's partitions taken from the broker rather than from an argument.
     *
     * Asking is the point. A partition count supplied by hand can disagree with the broker, and what
     * that produces — records piling into the partitions that exist while others are never written
     * to — reads as a data problem rather than the configuration mistake it is.
     */
    fun topic(name: TopicName): Topic {
        val answer = metadata(listOf(name))
        val partitions =
            answer.topics
                .firstOrNull { it.topic == name }
                ?.partitions
                ?.map { it.partition }
                .orEmpty()
        if (partitions.isEmpty()) throw ConnectionException("broker has no partitions for $name")
        return Topic(this, name, partitions)
    }

    override fun close() = socket.close()

    private fun readFrame(): ByteArray {
        val prefix = socket.readFully(Protocol.LENGTH_PREFIX_BYTES)
        val length =
            ((prefix[0].toInt() and 0xFF) shl 24) or
                ((prefix[1].toInt() and 0xFF) shl 16) or
                ((prefix[2].toInt() and 0xFF) shl 8) or
                (prefix[3].toInt() and 0xFF)

        // Bounded before anything is allocated: a length prefix off a socket is whatever the other
        // end said, and allocating what it asks for is how a process dies to one packet.
        if (length !in 1..Protocol.MAX_FRAME_BYTES) {
            throw ConnectionException("broker sent a frame of $length bytes")
        }
        return socket.readFully(length)
    }
}

/** One topic, so its name and its routing stop being arguments to every call. */
class Topic internal constructor(
    private val connection: BooblikConnection,
    val name: TopicName,
    val partitions: List<PartitionId>,
) {
    private var roundRobin = 0

    /**
     * Where a record with this key goes.
     *
     * A null key takes the next partition round-robin, and that counter advances on every call — so
     * asking and then sending is two turns of it and the records start skipping partitions. With a
     * key there is no such thing, the answer being a pure function of the key.
     */
    fun partitionFor(key: ByteArray?): PartitionId {
        if (key == null) {
            val chosen = partitions[roundRobin % partitions.size]
            roundRobin++
            return chosen
        }
        return partitions[Partitioner.Fnv1a.partitionFor(key, partitions.size)]
    }

    /**
     * Publishes one record, choosing its partition from [key].
     *
     * One record per request throws away the largest single performance factor there is: the
     * broker's own measurements put batches of a hundred at 4 335 482 records/s against 80 592 one
     * at a time. Use [BooblikConnection.produce] with a list whenever records are available
     * together.
     */
    fun send(
        record: ByteArray,
        key: ByteArray? = null,
        ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    ): ProduceResult? = connection.produce(name, partitionFor(key), listOf(record), ackPolicy)
}
