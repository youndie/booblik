package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
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

/** What the broker answered a FETCH with. [records] are already unframed. */
data class FetchResult(
    val correlationId: Int,
    val error: ErrorCode,
    val highWatermark: Offset,
    val records: List<ByteArray>,
    /** True when the response ended inside a record, which `maxBytes` makes routine. */
    val truncated: Boolean,
)

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
        while (body.remaining() >= Int.SIZE_BYTES) {
            val mark = body.position()
            val size = body.int
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
            records += record
        }
        if (body.remaining() > 0) truncated = true
        return FetchResult(correlationId, error, highWatermark, records, truncated)
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
