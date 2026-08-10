package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.ApiKey
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.Protocol
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** What the broker answered a PRODUCE with. */
data class ProduceResult(
    val correlationId: Int,
    val error: ErrorCode,
    val baseOffset: Offset,
    val logEndOffset: Offset,
)

/** What the broker answered a FETCH with. [records] are already unframed. */
data class FetchResult(
    val correlationId: Int,
    val error: ErrorCode,
    val highWatermark: Offset,
    val records: List<ByteArray>,
    val truncated: Boolean,
)

/**
 * A blocking client, enough to talk to a broker and to drive a load generator.
 *
 * Blocking and one connection per instance, deliberately. The load harness wants many independent
 * connections that each apply pressure at a fixed rate, and the simplest thing that does that is a
 * thread per connection with nothing clever underneath. A client sophisticated enough to share a
 * connection would make the harness measure the client.
 *
 * Sending and receiving are separate calls ([send] and [receive]) rather than one round trip. That
 * is what makes pipelining possible, and pipelining is the difference between measuring the broker
 * and measuring the network round trip.
 */
class BooblikClient(
    address: InetSocketAddress,
) : Closeable {
    private val channel =
        SocketChannel.open(address).apply {
            setOption(StandardSocketOptions.TCP_NODELAY, true)
        }

    private val lengthPrefix = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES)
    private var nextCorrelationId = 1

    /** Queues a PRODUCE. Returns the correlation id, or null with [AckPolicy.NONE] — no answer comes. */
    fun sendProduce(
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    ): Int? {
        val correlationId = nextCorrelationId++
        val topicBytes = topic.value.toByteArray(Charsets.UTF_8)
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES + 1 +
                Int.SIZE_BYTES + records.sumOf { Int.SIZE_BYTES + it.size }

        val buffer = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes)
        buffer.putInt(bodyBytes)
        buffer.putShort(ApiKey.PRODUCE.id)
        buffer.putShort(Protocol.VERSION)
        buffer.putInt(correlationId)
        buffer.putShort(topicBytes.size.toShort())
        buffer.put(topicBytes)
        buffer.putInt(partition.value)
        buffer.put(ackPolicy.ordinal.toByte())
        buffer.putInt(records.size)
        records.forEach { record ->
            buffer.putInt(record.size)
            buffer.put(record)
        }
        buffer.flip()
        writeFully(buffer)
        return if (ackPolicy == AckPolicy.NONE) null else correlationId
    }

    /** Queues a FETCH and returns its correlation id. */
    fun sendFetch(
        topic: TopicName,
        partition: PartitionId,
        fetchOffset: Offset,
        maxBytes: Int,
    ): Int {
        val correlationId = nextCorrelationId++
        val topicBytes = topic.value.toByteArray(Charsets.UTF_8)
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topicBytes.size + Int.SIZE_BYTES +
                Long.SIZE_BYTES + Int.SIZE_BYTES

        val buffer = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes)
        buffer.putInt(bodyBytes)
        buffer.putShort(ApiKey.FETCH.id)
        buffer.putShort(Protocol.VERSION)
        buffer.putInt(correlationId)
        buffer.putShort(topicBytes.size.toShort())
        buffer.put(topicBytes)
        buffer.putInt(partition.value)
        buffer.putLong(fetchOffset.value)
        buffer.putInt(maxBytes)
        buffer.flip()
        writeFully(buffer)
        return correlationId
    }

    /** Reads the next PRODUCE response. */
    fun receiveProduce(): ProduceResult {
        val body = readFrame()
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

    /** Reads the next FETCH response and unframes its records. */
    fun receiveFetch(): FetchResult {
        val body = readFrame()
        val correlationId = body.int
        val error = ErrorCode.of(body.short)
        if (error != ErrorCode.NONE) {
            return FetchResult(correlationId, error, Offset.ZERO, emptyList(), truncated = false)
        }
        val highWatermark = Offset(body.long)
        val payloadBytes = body.int

        val records = ArrayList<ByteArray>()
        var truncated = false
        while (body.remaining() >= Int.SIZE_BYTES) {
            val mark = body.position()
            val size = body.int
            if (size <= 0 || size > body.remaining()) {
                // `maxBytes` cuts on a byte boundary, not a record boundary, so the tail of a full
                // response is normally a record that does not fit. Discarding it and asking again
                // from the last complete offset is the client's job — the broker will not do it,
                // because parsing the batch is exactly what the zero-copy path avoids.
                body.position(mark)
                truncated = true
                break
            }
            val record = ByteArray(size)
            body.get(record)
            records += record
        }
        if (body.remaining() > 0) truncated = true
        check(payloadBytes >= 0)
        return FetchResult(correlationId, error, highWatermark, records, truncated)
    }

    private fun readFrame(): ByteBuffer {
        lengthPrefix.clear()
        readFully(lengthPrefix)
        lengthPrefix.flip()
        val length = lengthPrefix.int
        require(length in 1..Protocol.MAX_FRAME_BYTES) { "server sent a frame of $length bytes" }
        val body = ByteBuffer.allocate(length)
        readFully(body)
        body.flip()
        return body
    }

    private fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("broker closed the connection")
        }
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close() {
        channel.close()
    }
}
