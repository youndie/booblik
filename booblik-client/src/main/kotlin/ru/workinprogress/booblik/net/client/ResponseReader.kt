package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.MetadataResult
import ru.workinprogress.booblik.net.wire.ProduceResult
import ru.workinprogress.booblik.net.wire.Protocol
import ru.workinprogress.booblik.net.wire.ResponseDecoder
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/** What the broker answered a FETCH with. [records] are already unframed and checksum-verified. */
data class FetchResult(
    val correlationId: Int,
    val error: ErrorCode,
    val highWatermark: Offset,
    val records: List<ByteArray>,
    /** True when the response ended inside a record, which `maxBytes` makes routine. */
    val truncated: Boolean,
    /**
     * How big the dropped record is, when its header made it into the response; zero when the
     * response stopped inside the header itself.
     *
     * Here so that a caller can tell "nothing new to read" from "the next record is bigger than
     * `maxBytes` and never will be read", which are the same empty list otherwise. The number is
     * what `maxBytes` has to exceed, and it is what [RecordExceedsMaxBytesException] carries.
     */
    val truncatedRecordBytes: Int = 0,
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
 * Reads response frames off a socket and hands them to the codec.
 *
 * All three responses are decoded in `:booblik-protocol` now — PRODUCE and METADATA since M-134,
 * FETCH since M-140. What is left here is reading whole frames off a `SocketChannel`, which is the
 * only part that needs a JVM at all, plus the mapping from the protocol module's types to this
 * library's published ones.
 *
 * The FETCH decoder held out longest for a reason that turned out to be measurable and false: every
 * record has to be checksum-verified, `CRC32C` on the JVM is an intrinsic, and a decoder written for
 * common code was expected to cost a JVM reader. It does not (measurement 25).
 */
object ResponseReader {
    /** Reads one whole frame, without its length prefix. */
    fun readFrame(channel: SocketChannel): ByteArray {
        val prefix = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES)
        readFully(channel, prefix)
        prefix.flip()
        val length = prefix.int
        require(length in 1..Protocol.MAX_FRAME_BYTES) { "broker sent a frame of $length bytes" }

        val body = ByteBuffer.allocate(length)
        readFully(channel, body)
        return body.array()
    }

    fun produce(body: ByteArray): ProduceResult = ResponseDecoder.produce(body)

    fun metadata(body: ByteArray): MetadataResult = ResponseDecoder.metadata(body)

    /**
     * Unpacks a FETCH response, checksum and all.
     *
     * **The unpacking itself lives in `:booblik-protocol` since M-140.** There were two decoders for
     * this one response — this one over `ByteBuffer`, the shared one over `ByteArray` — and two
     * readings of the same bytes is one too many; what kept them apart was the belief that the
     * shared one would cost a JVM reader, `CRC32C` here being an intrinsic.
     *
     * Measured instead of believed (measurement 25). On the Linux box the two are
     * indistinguishable at 64 B and 1 KiB records and the shared one is **1.40× faster** at 8 KiB.
     * On the mac the same run said the opposite by 1.7× — with a ±100 % interval on one row, which
     * is the stand answering about itself rather than about the code.
     *
     * What is left here is the mapping, and it stays for one reason: [FetchResult] and
     * [CorruptRecordException] are this library's published types, and swapping them for the
     * protocol module's would break every caller for no gain a caller can see.
     */
    fun fetch(frame: ByteArray): FetchResult {
        val answer =
            try {
                ResponseDecoder.fetch(frame, Offset.ZERO)
            } catch (corrupt: ru.workinprogress.booblik.net.wire.CorruptRecordException) {
                // Translated rather than propagated: callers of this client catch the client's own
                // exception, and the offset the shared decoder reports is relative to the fetch
                // offset it was given — which is zero here, so it is the index within the response,
                // exactly what this exception has always carried.
                throw CorruptRecordException(corrupt.offset.value.toInt())
            }
        return FetchResult(
            answer.correlationId,
            answer.error,
            answer.highWatermark,
            answer.records,
            answer.truncated,
            answer.truncatedRecordBytes,
        )
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
