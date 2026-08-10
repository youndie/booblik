package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-70: the request a subscriber has to make before it can subscribe.
 *
 * Everything here exists because the set of partitions is fixed when the broker starts and used to
 * appear nowhere on the wire — so `subscribe(topic)` had to be told its partitions by hand.
 */
class MetadataTest {
    @Test
    fun `an empty request describes every topic the broker has`() =
        withBroker { client ->
            client.sendMetadata()
            val answer = client.receiveMetadata()

            assertEquals(ErrorCode.NONE, answer.error)
            assertEquals(listOf(TopicName("clicks"), TopicName("orders")), answer.topics.map { it.topic })
            assertEquals(listOf(1, 3), answer.topics.map { it.partitions.size })
            assertEquals(
                listOf(PartitionId(0), PartitionId(1), PartitionId(2)),
                answer.topics
                    .first { it.topic == TopicName("orders") }
                    .partitions
                    .map { it.partition },
            )
        }

    @Test
    fun `naming topics narrows the answer to them`() =
        withBroker { client ->
            client.sendMetadata(listOf(TopicName("clicks")))
            val answer = client.receiveMetadata()

            assertEquals(ErrorCode.NONE, answer.error)
            assertEquals(listOf(TopicName("clicks")), answer.topics.map { it.topic })
        }

    /**
     * A topic that is not here fails the whole request rather than being left out.
     *
     * Left out, "the topic does not exist" and "the topic exists and is empty" would arrive as the
     * same answer, and a subscriber acting on that difference would read nothing for ever without
     * ever being told why.
     */
    @Test
    fun `an unknown topic is an error, not an omission`() =
        withBroker { client ->
            client.sendMetadata(listOf(TopicName("orders"), TopicName("nope")))
            val answer = client.receiveMetadata()

            assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, answer.error)
            assertTrue(answer.topics.isEmpty())

            // The frame was refused, not the session.
            client.sendMetadata()
            assertEquals(ErrorCode.NONE, client.receiveMetadata().error)
        }

    /** The reason `logStartOffset` is on the wire at all: after retention it is not zero. */
    @Test
    fun `offsets describe the live log, and move as it is written`() =
        withBroker { client ->
            val orders = TopicName("orders")
            client.sendMetadata(listOf(orders))
            val before =
                client
                    .receiveMetadata()
                    .topics
                    .single()
                    .partitions
                    .first()
            assertEquals(Offset.ZERO, before.logStartOffset)
            assertEquals(Offset.ZERO, before.highWatermark)

            client.sendProduce(orders, PartitionId(0), List(4) { ByteArray(16) })
            client.receiveProduce()

            client.sendMetadata(listOf(orders))
            val after =
                client
                    .receiveMetadata()
                    .topics
                    .single()
                    .partitions
                    .first()
            assertEquals(Offset.ZERO, after.logStartOffset, "nothing has expired yet")
            assertEquals(Offset(4), after.highWatermark, "four records went in")
        }

    /** A broker with two topics, because one topic cannot show that grouping works. */
    private fun withBroker(body: (BooblikClient) -> Unit) {
        val dir = Files.createTempDirectory("booblik-metadata")
        val scope = CoroutineScope(SupervisorJob())
        val entries =
            listOf(
                TopicName("orders") to PartitionId(0),
                TopicName("orders") to PartitionId(1),
                TopicName("orders") to PartitionId(2),
                TopicName("clicks") to PartitionId(0),
            ).map { (topic, partition) ->
                val log = PartitionLog.open(dir.resolve("${topic.value}-${partition.value}"), SegmentMode.FILE_CHANNEL)
                PartitionRegistry.Key(topic, partition) to PartitionHandle(log, PartitionWriter(log, scope))
            }
        val server =
            BooblikServer(
                PartitionRegistry.of(*entries.toTypedArray()),
                ServerConfig(port = 0, bindAddress = "127.0.0.1"),
            )
        try {
            val address = server.start()
            BooblikClient(address).use { body(it) }
        } finally {
            server.close()
            scope.cancel()
            entries.forEach { runBlocking { it.second.log.close() } }
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }
}
