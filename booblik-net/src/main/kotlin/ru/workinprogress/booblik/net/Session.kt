package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.nio.Connection
import ru.workinprogress.booblik.net.wire.CorruptRequestException
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.FetchRequest
import ru.workinprogress.booblik.net.wire.ProduceRequest
import ru.workinprogress.booblik.net.wire.Protocol
import ru.workinprogress.booblik.net.wire.Request
import ru.workinprogress.booblik.net.wire.RequestDecoder
import ru.workinprogress.booblik.net.wire.ResponseEncoder
import java.io.EOFException
import java.nio.ByteBuffer

/**
 * The protocol loop for one connection: read a frame, answer it, repeat.
 *
 * Written once and run on both transports (see [Connection]).
 *
 * ## Pipelining and ordering
 *
 * Requests on one connection are served strictly in order, one at a time. That is not a
 * simplification to be removed later — the client is allowed to have several requests in flight,
 * and it matches them up by `correlationId` on the way back, so responses must come out in the
 * order the requests went in. Serving them concurrently would need a write queue and would reorder
 * exactly the case pipelining exists for.
 */
class Session(
    private val connection: Connection,
    private val partitions: PartitionRegistry,
    private val fetchMode: FetchMode,
) {
    private val lengthPrefix = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES)

    /** A reusable staging buffer for [FetchMode.HEAP]. Grown on demand, never per request. */
    private var heapStaging: ByteBuffer = ByteBuffer.allocate(INITIAL_STAGING_BYTES)

    suspend fun serve() {
        connection.use {
            while (true) {
                val frame = readFrame() ?: return
                handle(frame)
            }
        }
    }

    /** Returns null on a clean disconnect between frames — which is how clients normally leave. */
    private suspend fun readFrame(): ByteBuffer? {
        lengthPrefix.clear()
        try {
            connection.readFully(lengthPrefix)
        } catch (_: EOFException) {
            return null
        }
        lengthPrefix.flip()
        val length = lengthPrefix.int
        // The length came off the wire, so it is checked before it is used to allocate anything.
        // Unchecked, one small packet claiming a large frame is enough to end a 64 MiB broker.
        if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw CorruptRequestException("frame length $length outside 1..${Protocol.MAX_FRAME_BYTES}")
        }
        val body = ByteBuffer.allocate(length)
        connection.readFully(body)
        body.flip()
        return body
    }

    private suspend fun handle(frame: ByteBuffer) {
        val request =
            try {
                RequestDecoder.decode(frame)
            } catch (_: IllegalArgumentException) {
                // Catching the supertype is deliberate: `CorruptRequestException` covers what the
                // decoder checks, but `TopicName` and `PartitionId` validate themselves in their
                // own constructors and throw plain `IllegalArgumentException`. Both mean the same
                // thing on the wire.
                //
                // A frame we could not parse has no correlationId we can trust, so the answer
                // carries zero — and the connection stays open, because the framing was intact.
                respondError(correlationId = 0, code = ErrorCode.CORRUPT_REQUEST)
                return
            }

        val handle = partitions.find(request.topic, request.partition)
        if (handle == null) {
            respondError(request.header.correlationId, ErrorCode.UNKNOWN_TOPIC_OR_PARTITION)
            return
        }

        when (request) {
            is ProduceRequest -> produce(request, handle)
            is FetchRequest -> fetch(request, handle)
        }
    }

    private suspend fun produce(
        request: ProduceRequest,
        handle: PartitionHandle,
    ) {
        val tooLarge = request.records.any { !handle.log.hasRoomFor(it.size) }
        if (tooLarge) {
            respondError(request.header.correlationId, ErrorCode.RECORD_TOO_LARGE)
            return
        }

        val base = handle.writer.append(request.records, request.ackPolicy)
        // `NONE` gets no response at all — not an empty one. There is no offset to report, because
        // the offset does not exist until the writer reaches the batch, and a number the client
        // cannot check against anything would be worse than silence.
        if (request.ackPolicy == AckPolicy.NONE || base == null) return

        connection.writeFully(
            ResponseEncoder.produce(
                correlationId = request.header.correlationId,
                baseOffset = base,
                logEndOffset = handle.log.nextOffset,
            ),
        )
    }

    private suspend fun fetch(
        request: FetchRequest,
        handle: PartitionHandle,
    ) {
        val highWatermark = handle.log.nextOffset
        if (request.fetchOffset > highWatermark || request.fetchOffset < handle.log.logStartOffset) {
            respondError(request.header.correlationId, ErrorCode.OFFSET_OUT_OF_RANGE)
            return
        }
        // Reading exactly at the high watermark is legal and normal: it is what a caught-up consumer
        // does. It gets an empty response rather than an error.
        if (request.fetchOffset == highWatermark) {
            connection.writeFully(ResponseEncoder.fetchHeader(request.header.correlationId, highWatermark, 0))
            return
        }

        // The slice is held across the header **and** the body. Announcing a byte count and then
        // re-resolving the offset would race with retention, and the visible symptom would be a
        // response shorter than its own length prefix.
        val slice = handle.log.openFetch(request.fetchOffset, request.maxBytes)
        if (slice == null) {
            respondError(request.header.correlationId, ErrorCode.OFFSET_OUT_OF_RANGE)
            return
        }

        slice.use {
            connection.writeFully(
                ResponseEncoder.fetchHeader(request.header.correlationId, highWatermark, slice.bytes),
            )
            if (slice.bytes == 0) return

            when (fetchMode) {
                FetchMode.ZERO_COPY -> {
                    connection.transferFrom(slice.segment, slice.position, slice.bytes)
                }

                FetchMode.HEAP -> {
                    val staging = staging(slice.bytes)
                    slice.segment.readInto(slice.position, staging)
                    staging.flip()
                    connection.writeFully(staging)
                }
            }
        }
    }

    private suspend fun respondError(
        correlationId: Int,
        code: ErrorCode,
    ) {
        connection.writeFully(ResponseEncoder.error(correlationId, code))
    }

    private fun staging(bytes: Int): ByteBuffer {
        if (heapStaging.capacity() < bytes) heapStaging = ByteBuffer.allocate(bytes)
        heapStaging.clear()
        heapStaging.limit(bytes)
        return heapStaging
    }

    private companion object {
        const val INITIAL_STAGING_BYTES = 64 * 1024
    }
}
