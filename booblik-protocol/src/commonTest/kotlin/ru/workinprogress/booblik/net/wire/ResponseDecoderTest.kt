package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decodes frames built by hand from the specification, for the same reason the encoder test does. */
class ResponseDecoderTest {
    @Test
    fun `produce carries the base and the end of the log`() {
        val result = ResponseDecoder.produce(hex("0000000b" + "0000" + "000000000000002a" + "000000000000002c"))

        assertEquals(11, result.correlationId)
        assertEquals(ErrorCode.NONE, result.error)
        assertEquals(42L, result.baseOffset.value)
        assertEquals(44L, result.logEndOffset.value)
    }

    /**
     * An error response carries no offsets at all, so reading them would run off the end. The
     * decoder has to stop at the code, and this is the test that says so.
     */
    @Test
    fun `a refused produce stops at the error code`() {
        val result = ResponseDecoder.produce(hex("00000005" + "0001"))

        assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, result.error)
        assertEquals(0L, result.baseOffset.value)
    }

    @Test
    fun `metadata unpacks topics and their partitions in order`() {
        val result =
            ResponseDecoder.metadata(
                hex(
                    "00000003" + "0000" + "00000001" +
                        "0002" + "6162" + "00000002" +
                        "00000000" + "0000000000000000" + "0000000000000005" +
                        "00000001" + "0000000000000003" + "0000000000000009",
                ),
            )

        assertEquals(1, result.topics.size)
        val topic = result.topics.single()
        assertEquals("ab", topic.topic.value)
        assertEquals(listOf(0, 1), topic.partitions.map { it.partition.value })
        // logStartOffset is not always zero — retention moves it, and a client that assumes zero
        // asks for an offset the broker has already dropped.
        assertEquals(listOf(0L, 3L), topic.partitions.map { it.logStartOffset.value })
        assertEquals(listOf(5L, 9L), topic.partitions.map { it.highWatermark.value })
    }

    /**
     * A frame cut short by a broker restart is a connection problem, and it has to arrive as one.
     * An index-out-of-bounds from inside a decoder reads as a bug in the client instead.
     */
    @Test
    fun `a truncated frame is refused by name`() {
        val failure =
            assertFailsWith<TruncatedFrameException> {
                ResponseDecoder.metadata(hex("00000001" + "0000" + "00000001" + "0002"))
            }
        assertTrue(failure.message!!.contains("needed"), "the message should say how much was missing")
    }

    @Test
    fun `an unknown error code does not crash the decoder`() {
        // Anything unrecognised is treated as a corrupt request rather than throwing: the connection
        // is still framed correctly, and a client that died here would turn a new broker's new code
        // into an outage.
        assertEquals(ErrorCode.CORRUPT_REQUEST, ResponseDecoder.produce(hex("00000001" + "00ff")).error)
    }
}

internal fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { index ->
        val high = "0123456789abcdef".indexOf(text[index * 2])
        val low = "0123456789abcdef".indexOf(text[index * 2 + 1])
        ((high shl 4) or low).toByte()
    }

/**
 * The FETCH decoder: the checksum, the truncated tail, and the difference between them.
 *
 * Every frame here is built by hand from the specification rather than by the encoder, so a decoder
 * that agrees with an encoder they both got wrong still fails.
 */
class FetchDecoderTest {
    /** One record as the segment holds it: payloadSize, crc32c, payload. */
    private fun record(payload: ByteArray): ByteArray =
        ByteArray(8) { index ->
            when (index) {
                0 -> (payload.size ushr 24).toByte()
                1 -> (payload.size ushr 16).toByte()
                2 -> (payload.size ushr 8).toByte()
                3 -> payload.size.toByte()
                4 -> (crc32c(payload) ushr 24).toByte()
                5 -> (crc32c(payload) ushr 16).toByte()
                6 -> (crc32c(payload) ushr 8).toByte()
                else -> crc32c(payload).toByte()
            }
        } + payload

    private fun frame(
        payload: ByteArray,
        highWatermark: Long = 9,
        payloadBytes: Int = payload.size,
    ): ByteArray {
        val head =
            hex("0000002a" + "0000") +
                ByteArray(8) { (highWatermark ushr (56 - 8 * it)).toByte() } +
                ByteArray(4) { (payloadBytes ushr (24 - 8 * it)).toByte() }
        return head + payload
    }

    @Test
    fun `records come back whole and in the order the log holds them`() {
        val payloads = listOf("first".encodeToByteArray(), byteArrayOf(0), ByteArray(256) { it.toByte() })
        val result =
            ResponseDecoder.fetch(
                frame(payloads.fold(ByteArray(0)) { all, it -> all + record(it) }),
                Offset.ZERO,
            )

        assertEquals(ErrorCode.NONE, result.error)
        assertEquals(9L, result.highWatermark.value)
        assertEquals(payloads.map { it.toList() }, result.records.map { it.toList() })
        assertFalse(result.truncated)
    }

    @Test
    fun `a response cut inside a record drops the fragment and says how big it was`() {
        // maxBytes cuts on a byte boundary, so a full response normally ends this way. The fragment
        // must be dropped: returning it corrupts data, and calling it the end of the log stalls.
        val whole = record("A".repeat(100).encodeToByteArray())
        val cut = (whole + record("B".repeat(100).encodeToByteArray())).copyOf(whole.size + 42)

        val result = ResponseDecoder.fetch(frame(cut), Offset.ZERO)

        assertEquals(1, result.records.size)
        assertTrue(result.truncated)
        assertEquals(100, result.truncatedRecordBytes, "the caller needs the size to raise maxBytes")
    }

    @Test
    fun `a response cut inside a record header is truncation too`() {
        val whole = record("A".repeat(20).encodeToByteArray())
        val cut = (whole + record("B".repeat(20).encodeToByteArray())).copyOf(whole.size + 4)

        val result = ResponseDecoder.fetch(frame(cut), Offset.ZERO)

        assertEquals(1, result.records.size)
        assertTrue(result.truncated)
        assertEquals(0, result.truncatedRecordBytes, "there was no size field to read")
    }

    @Test
    fun `a record failing its checksum is refused by offset`() {
        val good = record("first".encodeToByteArray())
        val bad = record("second".encodeToByteArray())
        bad[7] = (bad[7].toInt() xor 1).toByte()

        val failure =
            assertFailsWith<CorruptRecordException> {
                ResponseDecoder.fetch(frame(good + bad), Offset(40))
            }
        assertEquals(41L, failure.offset.value, "the second record of a fetch that started at 40")
    }

    @Test
    fun `an empty response is not an error`() {
        // A caught-up consumer is the steady state, and this is what it gets.
        val result = ResponseDecoder.fetch(frame(ByteArray(0)), Offset(9))

        assertEquals(ErrorCode.NONE, result.error)
        assertTrue(result.records.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `a refusal carries no records and decodes none`() {
        val result = ResponseDecoder.fetch(hex("0000002a" + "0002"), Offset.ZERO)
        assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, result.error)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `a payloadBytes that disagrees with the frame is refused`() {
        // Redundant against the frame length, and that is what makes it worth checking: the field is
        // computed before the transfer starts and the bytes arrive afterwards.
        val payload = record("first".encodeToByteArray())
        assertFailsWith<IllegalStateException> {
            ResponseDecoder.fetch(frame(payload, payloadBytes = payload.size + 4), Offset.ZERO)
        }
    }
}
