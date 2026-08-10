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

    fun fetch(
        correlationId: Int,
        topic: TopicName,
        partition: PartitionId,
        fetchOffset: Offset,
        maxBytes: Int,
    ): ByteBuffer {
        val topicBytes = topic.value.toByteArray(Charsets.UTF_8)
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Long.SIZE_BYTES + Int.SIZE_BYTES

        return ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes).apply {
            putInt(bodyBytes)
            putShort(ApiKey.FETCH.id)
            putShort(Protocol.VERSION)
            putInt(correlationId)
            putShort(topicBytes.size.toShort())
            put(topicBytes)
            putInt(partition.value)
            putLong(fetchOffset.value)
            putInt(maxBytes)
            flip()
        }
    }
}
