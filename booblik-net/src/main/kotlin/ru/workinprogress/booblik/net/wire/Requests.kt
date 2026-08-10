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
}

/**
 * A request about one partition — which is every request except [MetadataRequest].
 *
 * Split out when METADATA arrived: `topic` and `partition` used to sit on [Request] itself, which
 * silently asserted that every request addresses a partition. METADATA is the first one that does
 * not, and a request type forced to invent a partition it does not have would have made the
 * session look it up and fail on a broker that is working perfectly.
 */
sealed interface PartitionRequest : Request {
    val topic: TopicName
    val partition: PartitionId
}

/** Empty [topics] means "everything this broker has". */
data class MetadataRequest(
    override val header: RequestHeader,
    val topics: List<TopicName>,
) : Request

data class ProduceRequest(
    override val header: RequestHeader,
    override val topic: TopicName,
    override val partition: PartitionId,
    val ackPolicy: AckPolicy,
    val records: List<ByteArray>,
) : PartitionRequest

data class FetchRequest(
    override val header: RequestHeader,
    override val topic: TopicName,
    override val partition: PartitionId,
    val fetchOffset: Offset,
    val maxBytes: Int,
    /** v2: how long the broker may hold this request when there is nothing to send. 0 = answer now. */
    val maxWaitMillis: Int = 0,
    /** v2: do not answer until this many bytes are available. 0 and 1 both mean "anything". */
    val minBytes: Int = 0,
) : PartitionRequest

/** Either a request, or an error the client can be told about by correlation id. */
sealed interface DecodeResult {
    data class Ok(
        val request: Request,
    ) : DecodeResult

    data class Failed(
        val correlationId: Int,
        val code: ErrorCode,
    ) : DecodeResult
}

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
     * Returns a [DecodeResult] rather than throwing, because a failure still has to be **answered**,
     * and answering needs the correlation id. The header is therefore read before anything is
     * validated: an unsupported api version is a request the client can be told about by name,
     * whereas a frame too short to hold a header is one we can only answer into the void.
     */
    fun decode(buffer: ByteBuffer): DecodeResult {
        val apiKeyId: Short
        val apiVersion: Short
        val correlationId: Int
        try {
            apiKeyId = buffer.getShortChecked("apiKey")
            apiVersion = buffer.getShortChecked("apiVersion")
            correlationId = buffer.getIntChecked("correlationId")
        } catch (_: CorruptRequestException) {
            // Nothing here is trustworthy, including any number we might echo back.
            return DecodeResult.Failed(UNKNOWN_CORRELATION_ID, ErrorCode.CORRUPT_REQUEST)
        }

        val apiKey = ApiKey.of(apiKeyId)
        // An unknown api key and an unknown version are the same class of problem — a client
        // speaking something this broker does not speak — and they share a code. `CORRUPT_REQUEST`
        // would be wrong: the frame is perfectly well formed, we simply do not implement it.
        if (apiKey == null || !Protocol.supports(apiKey, apiVersion)) {
            return DecodeResult.Failed(correlationId, ErrorCode.UNSUPPORTED_VERSION)
        }

        val header = RequestHeader(apiKey, apiVersion, correlationId)
        return try {
            // METADATA is dispatched before the topic and partition are read, because it has
            // neither. Reading them first — as this did while every request addressed a partition —
            // would make the decoder demand fields the frame does not contain.
            if (apiKey == ApiKey.METADATA) {
                return DecodeResult.Ok(decodeMetadata(header, buffer))
            }
            val topic = TopicName(buffer.getStringChecked())
            val partition = PartitionId(buffer.getIntChecked("partitionId").requireNonNegative("partitionId"))
            DecodeResult.Ok(
                when (apiKey) {
                    ApiKey.PRODUCE -> decodeProduce(header, topic, partition, buffer)
                    ApiKey.FETCH -> decodeFetch(header, topic, partition, buffer)
                    ApiKey.METADATA -> error("handled above")
                },
            )
        } catch (_: IllegalArgumentException) {
            // Catching the supertype is deliberate: the decoder throws `CorruptRequestException`,
            // but `TopicName` and `PartitionId` validate themselves in their own constructors and
            // throw plain `IllegalArgumentException`. Both mean the same thing on the wire.
            DecodeResult.Failed(correlationId, ErrorCode.CORRUPT_REQUEST)
        }
    }

    /** Echoed when the frame was too short to contain a correlation id worth echoing. */
    const val UNKNOWN_CORRELATION_ID = 0

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

    private fun decodeMetadata(
        header: RequestHeader,
        buffer: ByteBuffer,
    ): MetadataRequest {
        val count = buffer.getIntChecked("topicCount")
        if (count < 0) throw CorruptRequestException("topicCount must not be negative, got $count")
        // Same bound as PRODUCE uses on its record count: each name needs at least its own length
        // prefix, so a count larger than the bytes that follow cannot be honest, and the check has
        // to happen before anything is sized from it.
        if (count.toLong() * Short.SIZE_BYTES > buffer.remaining()) {
            throw CorruptRequestException("topicCount $count exceeds the ${buffer.remaining()} bytes that follow")
        }
        val topics = ArrayList<TopicName>(count)
        repeat(count) { topics += TopicName(buffer.getStringChecked()) }
        return MetadataRequest(header, topics)
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
        if (header.apiVersion < Protocol.FETCH_VERSION) {
            return FetchRequest(header, topic, partition, Offset(fetchOffset), maxBytes)
        }

        val maxWaitMillis = buffer.getIntChecked("maxWaitMillis")
        if (maxWaitMillis < 0) throw CorruptRequestException("maxWaitMillis must not be negative, got $maxWaitMillis")
        val minBytes = buffer.getIntChecked("minBytes")
        if (minBytes < 0) throw CorruptRequestException("minBytes must not be negative, got $minBytes")
        // Rejected here rather than in the session, because it is a property of the frame and not
        // of the log: such a request asks not to be answered until more bytes exist than it is
        // willing to receive, which no state of the broker can ever satisfy. Left to the session it
        // would be a request that waits out its timeout every single time, for ever.
        if (minBytes > maxBytes) {
            throw CorruptRequestException("minBytes $minBytes exceeds maxBytes $maxBytes; unsatisfiable")
        }
        return FetchRequest(header, topic, partition, Offset(fetchOffset), maxBytes, maxWaitMillis, minBytes)
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
