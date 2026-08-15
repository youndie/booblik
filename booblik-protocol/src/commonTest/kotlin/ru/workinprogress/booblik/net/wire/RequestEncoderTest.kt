package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the request frames byte for byte.
 *
 * Asserting against literal hex rather than against a decoder is the point: a decoder written from
 * the same misunderstanding agrees with the encoder perfectly. These bytes are what
 * `docs/api/protocol-wire.md` says, read off the document by hand.
 *
 * Runs on every target, which is why it is here rather than beside the JVM client. The whole reason
 * M-134 moved this encoder into a multiplatform module is that native and JVM must produce the same
 * bytes, and a test that only ever ran on one of them could not have noticed otherwise.
 */
class RequestEncoderTest {
    @Test
    fun `produce frames the header then the topic then the policy then every record`() {
        val frame =
            RequestEncoder.produce(
                correlationId = 7,
                topic = TopicName("ab"),
                partition = PartitionId(3),
                records = listOf(byteArrayOf(1, 2), byteArrayOf(0x7F, 0x80.toByte())),
                ackPolicy = AckPolicy.FORCED,
            )

        assertEquals(
            // 0x21 = 33 = header 8 + topic 2+2 + partition 4 + ack 1 + count 4 + two records of 4+2.
            // length          apiKey version corr     len  "ab"   partition  ack  count
            "00000021" + "0001" + "0001" + "00000007" + "0002" + "6162" + "00000003" + "02" + "00000002" +
                // record 1                    record 2
                "00000002" + "0102" + "00000002" + "7f80",
            frame.toHex(),
        )
    }

    @Test
    fun `the length prefix counts everything after itself`() {
        val frame = RequestEncoder.metadata(correlationId = 1, topics = listOf(TopicName("x")))
        val declared =
            ((frame[0].toInt() and 0xFF) shl 24) or
                ((frame[1].toInt() and 0xFF) shl 16) or
                ((frame[2].toInt() and 0xFF) shl 8) or
                (frame[3].toInt() and 0xFF)

        assertEquals(frame.size - Protocol.LENGTH_PREFIX_BYTES, declared)
    }

    @Test
    fun `metadata with no topics asks for everything`() {
        assertEquals(
            "0000000c" + "0003" + "0001" + "00000001" + "00000000",
            RequestEncoder.metadata(correlationId = 1, topics = emptyList()).toHex(),
        )
    }

    @Test
    fun `fetch always goes out as version two`() {
        val frame =
            RequestEncoder.fetch(
                correlationId = 2,
                topic = TopicName("t"),
                partition = PartitionId(0),
                fetchOffset = Offset(5),
                maxBytes = 1024,
            )
        // Bytes 4..5 are the apiKey, 6..7 the version. Emitting v1 when nothing is being waited for
        // would leave v2 exercised only in the branch nobody debugs.
        assertEquals("0002", frame.copyOfRange(4, 6).toHex())
        assertEquals("0002", frame.copyOfRange(6, 8).toHex())
    }

    /** UTF-8, not the platform default, and not UTF-16. */
    @Test
    fun `a topic name goes on the wire as utf-8`() {
        val frame = RequestEncoder.metadata(correlationId = 1, topics = listOf(TopicName("a.b_c-1")))
        assertEquals("0007" + "612e625f632d31", frame.copyOfRange(16, frame.size).toHex())
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0F]
    }
