package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.Protocol
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** What the broker answered a PRODUCE with. */
data class ProduceResult(
    val correlationId: Int,
    val error: ErrorCode,
    val baseOffset: Offset,
    val logEndOffset: Offset,
)

/** One partition, as the broker describes it. */
data class PartitionInfo(
    val partition: PartitionId,
    /** Where the **live** log starts: retention moves this, and it is not always zero. */
    val logStartOffset: Offset,
    val highWatermark: Offset,
)

/** One topic and its partitions, in the order the broker listed them. */
data class TopicInfo(
    val topic: TopicName,
    val partitions: List<PartitionInfo>,
)

/** What the broker answered a METADATA with. */
data class MetadataResult(
    val correlationId: Int,
    val error: ErrorCode,
    val topics: List<TopicInfo>,
)

/** What the broker answered a FETCH with. [records] are already unframed and checksum-verified. */
data class FetchResult(
    val correlationId: Int,
    val error: ErrorCode,
    val highWatermark: Offset,
    val records: List<ByteArray>,
    /** True when the response ended inside a record, which `maxBytes` makes routine. */
    val truncated: Boolean,
)

/**
 * A record that arrived not matching the checksum stored with it.
 *
 * The client is the only party on the read path that can notice. The broker streams segment bytes
 * to the socket without looking at them — that is what zero-copy means — so verification has to
 * happen where the bytes finally land.
 */
class CorruptRecordException(
    index: Int,
) : IllegalStateException("record $index in the fetch response fails its checksum")

/**
 * Reads response frames off a socket and unpacks them.
 *
 * Kept apart from both clients because both need it and neither should own it. Blocking by
 * signature: the pipelined client runs it on its own reader coroutine, the simple one calls it
 * inline.
 */
object ResponseReader {
    fun readFrame(channel: SocketChannel): ByteBuffer {
        val prefix = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES)
        readFully(channel, prefix)
        prefix.flip()
        val length = prefix.int
        require(length in 1..Protocol.MAX_FRAME_BYTES) { "broker sent a frame of $length bytes" }
        val body = ByteBuffer.allocate(length)
        readFully(channel, body)
        body.flip()
        return body
    }

    fun produce(body: ByteBuffer): ProduceResult {
        val correlationId = body.int
        val error = ErrorCode.of(body.short)
        if (error != ErrorCode.NONE) {
            return ProduceResult(correlationId, error, Offset.ZERO, Offset.ZERO)
        }
        // Read into locals rather than into the constructor call: both fields are `body.long`, and
        // which one is which would then depend on argument evaluation order.
        val baseOffset = Offset(body.long)
        val logEndOffset = Offset(body.long)
        return ProduceResult(correlationId, error, baseOffset, logEndOffset)
    }

    fun metadata(body: ByteBuffer): MetadataResult {
        val correlationId = body.int
        val error = ErrorCode.of(body.short)
        if (error != ErrorCode.NONE) return MetadataResult(correlationId, error, emptyList())

        val topicCount = body.int
        val topics = ArrayList<TopicInfo>(topicCount)
        repeat(topicCount) {
            val name = ByteArray(body.short.toInt() and 0xFFFF).also { body.get(it) }
            val partitionCount = body.int
            val partitions = ArrayList<PartitionInfo>(partitionCount)
            repeat(partitionCount) {
                // Locals, in order, for the same reason `produce` uses them: three reads of the
                // same buffer whose meaning is positional.
                val id = PartitionId(body.int)
                val logStartOffset = Offset(body.long)
                val highWatermark = Offset(body.long)
                partitions += PartitionInfo(id, logStartOffset, highWatermark)
            }
            topics += TopicInfo(TopicName(String(name, Charsets.UTF_8)), partitions)
        }
        return MetadataResult(correlationId, error, topics)
    }

    fun fetch(body: ByteBuffer): FetchResult {
        val correlationId = body.int
        val error = ErrorCode.of(body.short)
        if (error != ErrorCode.NONE) {
            return FetchResult(correlationId, error, Offset.ZERO, emptyList(), truncated = false)
        }
        val highWatermark = Offset(body.long)
        body.int // payloadBytes: the frame length already bounds the body, so this is redundant here

        val records = ArrayList<ByteArray>()
        var truncated = false
        while (body.remaining() >= RECORD_HEADER) {
            val mark = body.position()
            val size = body.int
            val expectedCrc = body.int
            if (size <= 0 || size > body.remaining()) {
                // `maxBytes` cuts on a byte boundary, not a record boundary, so the tail of a full
                // response is normally a record that does not fit. Dropping it and asking again
                // from the last complete offset is the client's job — the broker will not do it,
                // because parsing the batch is exactly what the zero-copy path avoids.
                body.position(mark)
                truncated = true
                break
            }
            val record = ByteArray(size)
            body.get(record)
            // Checked here and nowhere else on this path. A truncated tail is not corruption and
            // must not be reported as such, which is why the length check comes first.
            if (checksum(record) != expectedCrc) throw CorruptRecordException(records.size)
            records += record
        }
        if (body.remaining() > 0) truncated = true
        return FetchResult(correlationId, error, highWatermark, records, truncated)
    }

    /** `[int32 length][int32 crc]`, the same header the broker writes to disk. */
    private const val RECORD_HEADER = Int.SIZE_BYTES * 2

    private fun checksum(record: ByteArray): Int {
        val crc = java.util.zip.CRC32C()
        crc.update(record, 0, record.size)
        return crc.value.toInt()
    }

    fun readFully(
        channel: SocketChannel,
        buffer: ByteBuffer,
    ) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("broker closed the connection")
        }
    }
}
