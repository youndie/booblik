package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.ErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private fun records(
        count: Int,
        size: Int = 32,
    ) = List(count) { i -> ByteArray(size) { (i + it).toByte() } }

    @Test
    fun `a produced batch comes back byte for byte`() {
        // Run against every transport and both fetch paths. Four configurations exist so that two
        // measurements are possible (M-35, M-36); the only thing that makes those measurements
        // meaningful is that all four are otherwise indistinguishable, which is what this asserts.
        eachConfiguration { transport, fetchMode ->
            withServer(transport, fetchMode) { _, client ->
                val sent = records(10)
                client.sendProduce(TOPIC, PARTITION, sent)
                val produced = client.receiveProduce()

                assertEquals(ErrorCode.NONE, produced.error, "$transport/$fetchMode")
                assertEquals(Offset.ZERO, produced.baseOffset, "$transport/$fetchMode")
                assertEquals(Offset(10), produced.logEndOffset, "$transport/$fetchMode")

                client.sendFetch(TOPIC, PARTITION, Offset.ZERO, maxBytes = 1 shl 20)
                val fetched = client.receiveFetch()

                assertEquals(ErrorCode.NONE, fetched.error, "$transport/$fetchMode")
                assertEquals(Offset(10), fetched.highWatermark, "$transport/$fetchMode")
                assertEquals(sent.size, fetched.records.size, "$transport/$fetchMode")
                sent.forEachIndexed { i, expected ->
                    assertContentEquals(expected, fetched.records[i], "$transport/$fetchMode record $i")
                }
            }
        }
    }

    @Test
    fun `requests are answered in order, so several can be in flight`() {
        // The whole reason correlation ids exist. Send three without reading anything, then check
        // the answers come back in the order the requests went out.
        withServer { _, client ->
            val ids = (0 until 3).map { client.sendProduce(TOPIC, PARTITION, records(2))!! }
            val answers = (0 until 3).map { client.receiveProduce() }

            assertEquals(ids, answers.map { it.correlationId })
            assertEquals(listOf(0L, 2L, 4L), answers.map { it.baseOffset.value })
        }
    }

    @Test
    fun `ackPolicy NONE gets no reply at all`() {
        // Proved by what comes next: if the broker had answered, the following response would carry
        // the wrong correlation id.
        withServer { _, client ->
            assertEquals(null, client.sendProduce(TOPIC, PARTITION, records(1), AckPolicy.NONE))
            val id = client.sendProduce(TOPIC, PARTITION, records(1), AckPolicy.WRITTEN)!!
            val answer = client.receiveProduce()

            assertEquals(id, answer.correlationId, "the silent request must not have produced a frame")
            assertEquals(Offset(1), answer.baseOffset, "but it must have been written")
        }
    }

    @Test
    fun `an unknown partition is an error, not a new partition`() {
        withServer { _, client ->
            client.sendFetch(TopicName("nope"), PARTITION, Offset.ZERO, maxBytes = 1024)
            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, client.receiveFetch().error)

            client.sendProduce(TOPIC, PartitionId(7), records(1))
            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, client.receiveProduce().error)
        }
    }

    @Test
    fun `fetching past the end is an error, fetching exactly at the end is not`() {
        withServer { _, client ->
            client.sendProduce(TOPIC, PARTITION, records(3))
            client.receiveProduce()

            client.sendFetch(TOPIC, PARTITION, Offset(4), maxBytes = 1024)
            assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, client.receiveFetch().error)

            // Reading at the high watermark is what a caught-up consumer does every time it polls.
            // Answering that with an error would make the normal case look like a failure.
            client.sendFetch(TOPIC, PARTITION, Offset(3), maxBytes = 1024)
            val atEnd = client.receiveFetch()
            assertEquals(ErrorCode.NONE, atEnd.error)
            assertEquals(emptyList(), atEnd.records)
            assertEquals(Offset(3), atEnd.highWatermark)
        }
    }

    @Test
    fun `maxBytes cuts on a byte boundary and the client drops the partial tail`() {
        eachConfiguration { transport, fetchMode ->
            withServer(transport, fetchMode) { _, client ->
                val sent = records(10, size = 100)
                client.sendProduce(TOPIC, PARTITION, sent)
                client.receiveProduce()

                // Two and a half records' worth: 108 bytes each on disk with the header.
                client.sendFetch(TOPIC, PARTITION, Offset.ZERO, maxBytes = 270)
                val fetched = client.receiveFetch()

                assertEquals(2, fetched.records.size, "$transport/$fetchMode")
                assertTrue(fetched.truncated, "$transport/$fetchMode: the third record was cut")
                assertContentEquals(sent[0], fetched.records[0], "$transport/$fetchMode")
                assertContentEquals(sent[1], fetched.records[1], "$transport/$fetchMode")
            }
        }
    }

    @Test
    fun `a fetch that spans a segment boundary stops at it`() {
        // One call, one file. The consumer asks again with the offset it reached, and the second
        // call lands in the next segment.
        withServer(segmentCapacity = 1024) { _, client ->
            val sent = records(12, size = 200)
            client.sendProduce(TOPIC, PARTITION, sent)
            client.receiveProduce()

            client.sendFetch(TOPIC, PARTITION, Offset.ZERO, maxBytes = 1 shl 20)
            val first = client.receiveFetch()
            assertEquals(4, first.records.size, "1024 / (8 + 200) = 4 records per segment")

            client.sendFetch(TOPIC, PARTITION, Offset(4), maxBytes = 1 shl 20)
            val second = client.receiveFetch()
            assertEquals(4, second.records.size)
            assertContentEquals(sent[4], second.records[0])
        }
    }

    @Test
    fun `a batch too large for a segment is refused`() {
        withServer(segmentCapacity = 1024) { _, client ->
            client.sendProduce(TOPIC, PARTITION, listOf(ByteArray(2000)))
            assertEquals(ErrorCode.RECORD_TOO_LARGE, client.receiveProduce().error)
        }
    }
}
