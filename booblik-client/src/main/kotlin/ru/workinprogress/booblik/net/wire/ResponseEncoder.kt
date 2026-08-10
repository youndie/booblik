package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import java.nio.ByteBuffer

/**
 * Builds response frames.
 *
 * A FETCH response is the odd one out and shapes this whole object: its body does not exist in any
 * buffer. The bytes go from the page cache to the socket without passing through the JVM, so what
 * gets encoded here is only the **header**, with a length that promises how many bytes the server
 * is about to stream after it. Assembling the whole response first would undo the one thing the
 * read path is built for.
 */
object ResponseEncoder {
    /** `[int32 correlationId][int16 errorCode][int64 baseOffset][int64 logEndOffset]`. */
    fun produce(
        correlationId: Int,
        baseOffset: Offset,
        logEndOffset: Offset,
    ): ByteBuffer =
        frame(promised = Long.SIZE_BYTES * 2, inline = Long.SIZE_BYTES * 2) { buffer ->
            buffer.putInt(correlationId)
            buffer.putShort(ErrorCode.NONE.id)
            buffer.putLong(baseOffset.value)
            buffer.putLong(logEndOffset.value)
        }

    /**
     * Header of a FETCH response. [payloadBytes] is counted into the frame length even though those
     * bytes are streamed separately — the client reads one framed message either way.
     */
    fun fetchHeader(
        correlationId: Int,
        highWatermark: Offset,
        payloadBytes: Int,
    ): ByteBuffer =
        frame(
            // Promised: the client will read this many bytes of body.
            promised = Long.SIZE_BYTES + Int.SIZE_BYTES + payloadBytes,
            // Carried: only the two fields. The rest arrives by `transferTo`.
            inline = Long.SIZE_BYTES + Int.SIZE_BYTES,
        ) { buffer ->
            buffer.putInt(correlationId)
            buffer.putShort(ErrorCode.NONE.id)
            buffer.putLong(highWatermark.value)
            buffer.putInt(payloadBytes)
        }

    /**
     * `[int32 topicCount]` then, per topic, `[u16 nameLength][name][int32 partitionCount]` and, per
     * partition, `[int32 id][int64 logStartOffset][int64 highWatermark]`.
     *
     * Three numbers per partition, and the first two are the point. `logStartOffset` is where the
     * **live** log begins after retention — without it "read from the beginning" would have to mean
     * offset zero, which is `OFFSET_OUT_OF_RANGE` on any topic that has ever expired a segment.
     * `highWatermark` answers "read only what is new" and lets a reader compute its lag without a
     * probing FETCH.
     */
    fun metadata(
        correlationId: Int,
        topics: List<TopicMetadata>,
    ): ByteBuffer {
        val names = topics.map { it.name.value.toByteArray(Charsets.UTF_8) }
        val bodyBytes =
            Int.SIZE_BYTES +
                topics.indices.sumOf { i ->
                    Short.SIZE_BYTES + names[i].size + Int.SIZE_BYTES +
                        topics[i].partitions.size * (Int.SIZE_BYTES + Long.SIZE_BYTES * 2)
                }
        return frame(promised = bodyBytes, inline = bodyBytes) { buffer ->
            buffer.putInt(correlationId)
            buffer.putShort(ErrorCode.NONE.id)
            buffer.putInt(topics.size)
            topics.forEachIndexed { i, topic ->
                buffer.putShort(names[i].size.toShort())
                buffer.put(names[i])
                buffer.putInt(topic.partitions.size)
                for (partition in topic.partitions) {
                    buffer.putInt(partition.id.value)
                    buffer.putLong(partition.logStartOffset.value)
                    buffer.putLong(partition.highWatermark.value)
                }
            }
        }
    }

    /**
     * A failure carries no body. Every error the broker can answer with fits in the code, and a
     * message would only be a second, less reliable copy of it.
     */
    fun error(
        correlationId: Int,
        code: ErrorCode,
    ): ByteBuffer =
        frame(promised = 0, inline = 0) { buffer ->
            buffer.putInt(correlationId)
            buffer.putShort(code.id)
        }

    /**
     * @param promised body bytes the frame length announces to the client
     * @param inline body bytes this buffer actually carries — the same as [promised] for every
     *   response except a FETCH header, where the difference is the point
     */
    private inline fun frame(
        promised: Int,
        inline: Int,
        fill: (ByteBuffer) -> Unit,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + Protocol.RESPONSE_HEADER_BYTES + inline)
        buffer.putInt(Protocol.RESPONSE_HEADER_BYTES + promised)
        fill(buffer)
        buffer.flip()
        return buffer
    }
}

/** One partition as METADATA describes it. */
data class PartitionMetadata(
    val id: ru.workinprogress.booblik.PartitionId,
    val logStartOffset: Offset,
    val highWatermark: Offset,
)

/** One topic and its partitions, in order. */
data class TopicMetadata(
    val name: ru.workinprogress.booblik.TopicName,
    val partitions: List<PartitionMetadata>,
)
