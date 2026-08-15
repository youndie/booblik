package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy

/**
 * Builds request frames — the client's half of the codec, and the only place that knows the layout
 * from this side.
 *
 * Shared by every client on purpose. There are several — a blocking one for tests, a pipelined one
 * for real use, and a Kotlin/Native one — and hand-written encoders drift apart at exactly the rate
 * nobody notices: the tests keep passing against the encoder the tests use.
 *
 * Returns a `ByteArray` including its own length prefix, so a caller writes one thing to one socket.
 */
object RequestEncoder {
    fun produce(
        correlationId: Int,
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy,
    ): ByteArray {
        val topicBytes = topic.value.encodeToByteArray()
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Byte.SIZE_BYTES + Int.SIZE_BYTES + records.sumOf { Int.SIZE_BYTES + it.size }

        val writer = ByteWriter(Protocol.LENGTH_PREFIX_BYTES + bodyBytes)
        writer.putInt(bodyBytes)
        writer.putShort(ApiKey.PRODUCE.id)
        writer.putShort(Protocol.VERSION)
        writer.putInt(correlationId)
        writer.putShort(topicBytes.size.toShort())
        writer.put(topicBytes)
        writer.putInt(partition.value)
        writer.putByte(ackPolicy.ordinal.toByte())
        writer.putInt(records.size)
        records.forEach {
            writer.putInt(it.size)
            writer.put(it)
        }
        return writer.bytes
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
    ): ByteArray {
        val topicBytes = topic.value.encodeToByteArray()
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Long.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES

        val writer = ByteWriter(Protocol.LENGTH_PREFIX_BYTES + bodyBytes)
        writer.putInt(bodyBytes)
        writer.putShort(ApiKey.FETCH.id)
        writer.putShort(Protocol.FETCH_VERSION)
        writer.putInt(correlationId)
        writer.putShort(topicBytes.size.toShort())
        writer.put(topicBytes)
        writer.putInt(partition.value)
        writer.putLong(fetchOffset.value)
        writer.putInt(maxBytes)
        writer.putInt(maxWaitMillis)
        writer.putInt(minBytes)
        return writer.bytes
    }

    /** Empty [topics] asks for everything this broker has. */
    fun metadata(
        correlationId: Int,
        topics: List<TopicName>,
    ): ByteArray {
        val names = topics.map { it.value.encodeToByteArray() }
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Int.SIZE_BYTES + names.sumOf { Short.SIZE_BYTES + it.size }

        val writer = ByteWriter(Protocol.LENGTH_PREFIX_BYTES + bodyBytes)
        writer.putInt(bodyBytes)
        writer.putShort(ApiKey.METADATA.id)
        writer.putShort(Protocol.VERSION)
        writer.putInt(correlationId)
        writer.putInt(names.size)
        names.forEach {
            writer.putShort(it.size.toShort())
            writer.put(it)
        }
        return writer.bytes
    }
}
