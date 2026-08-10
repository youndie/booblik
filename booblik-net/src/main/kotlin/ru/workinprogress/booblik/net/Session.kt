package ru.workinprogress.booblik.net

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.nio.Connection
import ru.workinprogress.booblik.net.wire.CorruptRequestException
import ru.workinprogress.booblik.net.wire.DecodeResult
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.FetchRequest
import ru.workinprogress.booblik.net.wire.MetadataRequest
import ru.workinprogress.booblik.net.wire.PartitionMetadata
import ru.workinprogress.booblik.net.wire.PartitionRequest
import ru.workinprogress.booblik.net.wire.ProduceRequest
import ru.workinprogress.booblik.net.wire.Protocol
import ru.workinprogress.booblik.net.wire.Request
import ru.workinprogress.booblik.net.wire.RequestDecoder
import ru.workinprogress.booblik.net.wire.ResponseEncoder
import ru.workinprogress.booblik.net.wire.TopicMetadata
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
    private val metrics: Metrics = Metrics(),
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
        // The connection stays open on a decode failure: the framing was intact, so the client is
        // still speaking the protocol — it just said something this broker cannot serve.
        val request =
            when (val decoded = RequestDecoder.decode(frame)) {
                is DecodeResult.Ok -> {
                    decoded.request
                }

                is DecodeResult.Failed -> {
                    respondError(decoded.correlationId, decoded.code)
                    return
                }
            }

        // Answered before the partition lookup, because it is the one request that does not name
        // a partition to look up.
        if (request is MetadataRequest) {
            metadata(request)
            return
        }

        val partitioned = request as PartitionRequest
        val handle = partitions.find(partitioned.topic, partitioned.partition)
        if (handle == null) {
            respondError(request.header.correlationId, ErrorCode.UNKNOWN_TOPIC_OR_PARTITION)
            return
        }

        when (partitioned) {
            is ProduceRequest -> produce(partitioned, handle)
            is FetchRequest -> fetch(partitioned, handle)
        }
    }

    /**
     * Answers what exists and where each partition currently begins and ends.
     *
     * A named topic this broker does not have fails the **whole** request with
     * `UNKNOWN_TOPIC_OR_PARTITION`, rather than being quietly left out of the answer. Omitting it
     * would make "the topic is not here" and "the topic is here and empty" arrive as the same
     * response, and a subscriber acting on that difference would sit reading nothing for ever.
     * A request naming no topics asks for everything and cannot hit this.
     */
    private suspend fun metadata(request: MetadataRequest) {
        val described = partitions.describe()
        val wanted =
            if (request.topics.isEmpty()) {
                described
            } else {
                val missing = request.topics.firstOrNull { it !in described }
                if (missing != null) {
                    respondError(request.header.correlationId, ErrorCode.UNKNOWN_TOPIC_OR_PARTITION)
                    return
                }
                described.filterKeys { it in request.topics }
            }

        val topics =
            wanted.map { (name, handles) ->
                TopicMetadata(
                    name,
                    handles.map { (id, handle) ->
                        PartitionMetadata(
                            id = id,
                            logStartOffset = handle.log.logStartOffset,
                            // Read from the log rather than from the writer's published watermark:
                            // this is a question about what is readable now, and the log is what
                            // answers it. The writer's `StateFlow` exists to signal *changes*.
                            highWatermark = handle.log.nextOffset,
                        )
                    },
                )
            }
        connection.writeFully(ResponseEncoder.metadata(request.header.correlationId, topics))
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

        metrics.onProduce()
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
        // Held **before** the slice is opened, never after. A slice keeps its segment alive, so
        // waiting with one open would block retention for the whole wait — a minute of held
        // deletion because one consumer is idle. The price is that the log can move underneath the
        // wait, which is why every check below is made after it and not before.
        if (request.maxWaitMillis > 0) awaitRecords(request, handle)

        val highWatermark = handle.log.nextOffset
        if (request.fetchOffset > highWatermark || request.fetchOffset < handle.log.logStartOffset) {
            respondError(request.header.correlationId, ErrorCode.OFFSET_OUT_OF_RANGE)
            return
        }
        // Reading exactly at the high watermark is legal and normal: it is what a caught-up consumer
        // does. It gets an empty response rather than an error.
        if (request.fetchOffset == highWatermark) {
            metrics.onFetch(0)
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
            metrics.onFetch(slice.bytes)
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

    /**
     * Waits until the request can be satisfied, the deadline passes, or the log moves past it.
     *
     * The loop waits for a **new** watermark each time rather than for one greater than the fetch
     * offset. Those differ exactly when `minBytes` asks for more than has arrived: with the latter
     * condition the flow would return its current value immediately and the loop would spin at full
     * speed until the deadline, which is a busy wait wearing the costume of a suspend.
     *
     * Returning early on a closed or moved log is deliberate: the checks in [fetch] run afterwards
     * and know how to answer, and duplicating them here would give two places that decide what
     * `OFFSET_OUT_OF_RANGE` means.
     */
    private suspend fun awaitRecords(
        request: FetchRequest,
        handle: PartitionHandle,
    ) {
        val wait = minOf(request.maxWaitMillis, Protocol.MAX_FETCH_WAIT_MILLIS).toLong()
        val minBytes = maxOf(request.minBytes, 1)
        val deadline = System.nanoTime() + wait * 1_000_000
        var seen = handle.writer.highWatermark.value

        metrics.onFetchHeld()
        try {
            while (available(request, handle) < minBytes) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) return
                withTimeoutOrNull(remaining) {
                    seen = handle.writer.highWatermark.first { it > seen }
                } ?: return
            }
        } finally {
            metrics.onFetchReleased()
        }
    }

    /**
     * How many bytes this request could take right now.
     *
     * Opens and immediately closes a slice: that is an index lookup and a subtraction, not I/O, and
     * it is the only thing that knows about the byte boundary `minBytes` is expressed in. Holding
     * the slice across the wait is what this must not do.
     */
    private fun available(
        request: FetchRequest,
        handle: PartitionHandle,
    ): Int = handle.log.openFetch(request.fetchOffset, request.maxBytes)?.use { it.bytes } ?: 0

    private suspend fun respondError(
        correlationId: Int,
        code: ErrorCode,
    ) {
        metrics.onError()
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
