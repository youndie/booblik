package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import java.nio.ByteBuffer

/** A decoded request header. The body that follows depends on [apiKey]. */
data class RequestHeader(
    val apiKey: ApiKey,
    val apiVersion: Short,
    val correlationId: Int,
)

sealed interface Request {
    val header: RequestHeader
    val topic: TopicName
    val partition: PartitionId
}

data class ProduceRequest(
    override val header: RequestHeader,
    override val topic: TopicName,
    override val partition: PartitionId,
    val ackPolicy: AckPolicy,
    val records: List<ByteArray>,
) : Request

data class FetchRequest(
    override val header: RequestHeader,
    override val topic: TopicName,
    override val partition: PartitionId,
    val fetchOffset: Offset,
    val maxBytes: Int,
) : Request

/**
 * Turns bytes into requests.
 *
 * Every read is bounds-checked against the buffer, and every length that came off the wire is
 * checked before it is used to size anything. That is the whole job: a decoder is the one place
 * where a remote party chooses the numbers, so "the client would not send that" is not an argument
 * available here.
 */
object RequestDecoder {
    /**
     * Decodes a complete frame body — everything after the `int32` length prefix.
     *
     * @throws CorruptRequestException if the frame does not parse.
     */
    fun decode(buffer: ByteBuffer): Request {
        val apiKeyId = buffer.getShortChecked("apiKey")
        val apiVersion = buffer.getShortChecked("apiVersion")
        val correlationId = buffer.getIntChecked("correlationId")

        val apiKey =
            ApiKey.of(apiKeyId)
                ?: throw CorruptRequestException("unknown apiKey $apiKeyId")
        val header = RequestHeader(apiKey, apiVersion, correlationId)

        val topic = TopicName(buffer.getStringChecked())
        val partition = PartitionId(buffer.getIntChecked("partitionId").requireNonNegative("partitionId"))

        return when (apiKey) {
            ApiKey.PRODUCE -> decodeProduce(header, topic, partition, buffer)
            ApiKey.FETCH -> decodeFetch(header, topic, partition, buffer)
        }
    }

    private fun decodeProduce(
        header: RequestHeader,
        topic: TopicName,
        partition: PartitionId,
        buffer: ByteBuffer,
    ): ProduceRequest {
        val ackId = buffer.getByteChecked("ackPolicy").toInt()
        val ackPolicy =
            AckPolicy.entries.getOrNull(ackId)
                ?: throw CorruptRequestException("unknown ackPolicy $ackId")

        val count = buffer.getIntChecked("recordCount")
        if (count <= 0) throw CorruptRequestException("recordCount must be positive, got $count")
        // Bounded by what is actually in the buffer before anything is allocated: each record needs
        // at least its own length prefix, so a count larger than that cannot be honest.
        if (count.toLong() * Int.SIZE_BYTES > buffer.remaining()) {
            throw CorruptRequestException("recordCount $count exceeds the ${buffer.remaining()} bytes that follow")
        }

        val records = ArrayList<ByteArray>(count)
        repeat(count) { i ->
            val size = buffer.getIntChecked("record[$i].size")
            if (size <= 0) throw CorruptRequestException("record[$i] has size $size; empty records are not storable")
            if (size > buffer.remaining()) {
                throw CorruptRequestException("record[$i] claims $size bytes, ${buffer.remaining()} remain")
            }
            val payload = ByteArray(size)
            buffer.get(payload)
            records += payload
        }
        return ProduceRequest(header, topic, partition, ackPolicy, records)
    }

    private fun decodeFetch(
        header: RequestHeader,
        topic: TopicName,
        partition: PartitionId,
        buffer: ByteBuffer,
    ): FetchRequest {
        val fetchOffset = buffer.getLongChecked("fetchOffset")
        if (fetchOffset < 0) throw CorruptRequestException("fetchOffset must be non-negative, got $fetchOffset")
        val maxBytes = buffer.getIntChecked("maxBytes")
        if (maxBytes <= 0) throw CorruptRequestException("maxBytes must be positive, got $maxBytes")
        return FetchRequest(header, topic, partition, Offset(fetchOffset), maxBytes)
    }

    private fun ByteBuffer.getStringChecked(): String {
        val length = getShortChecked("topicNameLength").toInt() and 0xFFFF
        if (length == 0) throw CorruptRequestException("topic name must not be empty")
        if (length >
            remaining()
        ) {
            throw CorruptRequestException("topic name claims $length bytes, ${remaining()} remain")
        }
        val bytes = ByteArray(length)
        get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun ByteBuffer.getByteChecked(field: String): Byte {
        if (remaining() < 1) throw CorruptRequestException("truncated before $field")
        return get()
    }

    private fun ByteBuffer.getShortChecked(field: String): Short {
        if (remaining() < Short.SIZE_BYTES) throw CorruptRequestException("truncated before $field")
        return short
    }

    private fun ByteBuffer.getIntChecked(field: String): Int {
        if (remaining() < Int.SIZE_BYTES) throw CorruptRequestException("truncated before $field")
        return int
    }

    private fun ByteBuffer.getLongChecked(field: String): Long {
        if (remaining() < Long.SIZE_BYTES) throw CorruptRequestException("truncated before $field")
        return long
    }

    private fun Int.requireNonNegative(field: String): Int {
        if (this < 0) throw CorruptRequestException("$field must be non-negative, got $this")
        return this
    }
}
