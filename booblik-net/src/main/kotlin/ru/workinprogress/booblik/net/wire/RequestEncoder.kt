package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import java.nio.ByteBuffer

/**
 * Builds request frames — the mirror of [RequestDecoder], and the only place that knows the layout
 * from the client side.
 *
 * Shared by both clients on purpose. There are two of them (a blocking one for tests and a
 * pipelined one for real use), and two hand-written encoders would drift apart at exactly the rate
 * nobody notices: the tests would keep passing against the encoder the tests use.
 */
object RequestEncoder {
    fun produce(
        correlationId: Int,
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy,
    ): ByteBuffer {
        val topicBytes = topic.value.toByteArray(Charsets.UTF_8)
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Byte.SIZE_BYTES + Int.SIZE_BYTES + records.sumOf { Int.SIZE_BYTES + it.size }

        return ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes).apply {
            putInt(bodyBytes)
            putShort(ApiKey.PRODUCE.id)
            putShort(Protocol.VERSION)
            putInt(correlationId)
            putShort(topicBytes.size.toShort())
            put(topicBytes)
            putInt(partition.value)
            put(ackPolicy.ordinal.toByte())
            putInt(records.size)
            records.forEach {
                putInt(it.size)
                put(it)
            }
            flip()
        }
    }

    /**
     * Always emits v2, including when nothing is being waited for.
     *
     * One code path rather than two: a client that switched versions depending on its arguments
     * would exercise v1 only in the branch nobody debugs. The broker still decodes v1, for anyone
     * else's client — that is what version support is for.
     */
    fun fetch(
        correlationId: Int,
        topic: TopicName,
        partition: PartitionId,
        fetchOffset: Offset,
        maxBytes: Int,
        maxWaitMillis: Int = 0,
        minBytes: Int = 0,
    ): ByteBuffer {
        val topicBytes = topic.value.toByteArray(Charsets.UTF_8)
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Long.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES

        return ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes).apply {
            putInt(bodyBytes)
            putShort(ApiKey.FETCH.id)
            putShort(Protocol.FETCH_VERSION)
            putInt(correlationId)
            putShort(topicBytes.size.toShort())
            put(topicBytes)
            putInt(partition.value)
            putLong(fetchOffset.value)
            putInt(maxBytes)
            putInt(maxWaitMillis)
            putInt(minBytes)
            flip()
        }
    }

    /** Empty [topics] asks for everything this broker has. */
    fun metadata(
        correlationId: Int,
        topics: List<TopicName>,
    ): ByteBuffer {
        val names = topics.map { it.value.toByteArray(Charsets.UTF_8) }
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Int.SIZE_BYTES + names.sumOf { Short.SIZE_BYTES + it.size }

        return ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes).apply {
            putInt(bodyBytes)
            putShort(ApiKey.METADATA.id)
            putShort(Protocol.VERSION)
            putInt(correlationId)
            putInt(names.size)
            names.forEach {
                putShort(it.size.toShort())
                put(it)
            }
            flip()
        }
    }
}
