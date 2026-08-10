package ru.workinprogress.booblik.net

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.OffsetStore
import ru.workinprogress.booblik.net.client.StartPosition
import ru.workinprogress.booblik.net.client.SubscriptionConfig
import ru.workinprogress.booblik.net.client.checkpointing
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * M-71: the subscription, against a real broker over real sockets.
 *
 * `runTest`'s virtual clock is deliberately not used: none of this is a `Flow` over nothing, it is
 * a `Flow` over a socket, and virtual time does not advance on network I/O. Turbine still earns its
 * place — `expectNoEvents` and a bounded `awaitItem` say exactly what these scenarios are about.
 */
class SubscriptionTest {
    @Test
    fun `follow delivers what arrives after it started, and nothing before`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 3)

            BooblikSubscriber(address, FAST).use { subscriber ->
                subscriber.follow(TOPIC, StartPosition.Latest, listOf(PartitionId(0))).test(timeout = 10.seconds) {
                    // The delay is what makes this assertion mean anything: `expectNoEvents` looks
                    // at what has already arrived, it does not wait. Without the pause the flow
                    // would still be doing METADATA and the check would pass on any implementation,
                    // including one that hands over the three records already in the log.
                    delay(300)
                    expectNoEvents()

                    withContext(Dispatchers.IO) { produce(PartitionId(0), 2) }
                    val batch = awaitItem()
                    assertEquals(Offset(3), batch.baseOffset, "started at the end of what existed")
                    assertEquals(2, batch.records.size)
                    assertEquals(Offset(5), batch.nextOffset)
                    assertEquals(0, batch.lag, "nothing else was written")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `replay reads what is there and completes`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 4)

            BooblikSubscriber(address, FAST).use { subscriber ->
                val batches = subscriber.replay(TOPIC, StartPosition.Earliest, listOf(PartitionId(0))).toList()
                assertEquals(4, batches.sumOf { it.records.size })
                assertEquals(Offset(4), batches.last().nextOffset)
            }
        }

    /**
     * The reason `replay` pins the watermark at start: measured continuously it would never end on
     * a topic anybody is writing to, which is the one case it is called for.
     */
    @Test
    fun `replay ends even while the topic is still being written`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 4)

            BooblikSubscriber(address, FAST).use { subscriber ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val writer = scope.launch { repeat(20) { produce(PartitionId(0), 1) } }
                try {
                    val batches = subscriber.replay(TOPIC, StartPosition.Earliest, listOf(PartitionId(0))).toList()
                    assertTrue(
                        batches.sumOf { it.records.size } >= 4,
                        "replay must at least deliver what existed when it started",
                    )
                } finally {
                    writer.cancel()
                    scope.cancel()
                }
            }
        }

    /** Partitions are discovered, not named — the whole point of M-70. */
    @Test
    fun `a subscription covers every partition without being told them`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 2)
            produce(PartitionId(1), 3)

            BooblikSubscriber(address, FAST).use { subscriber ->
                assertEquals(listOf(PartitionId(0), PartitionId(1)), subscriber.partitionsOf(TOPIC))

                val batches = subscriber.replay(TOPIC, StartPosition.Earliest).toList()
                assertEquals(5, batches.sumOf { it.records.size })
                assertEquals(
                    setOf(PartitionId(0), PartitionId(1)),
                    batches.map { it.partition }.toSet(),
                    "both partitions delivered; the order between them is not promised",
                )
            }
        }

    @Test
    fun `starting At an offset reads from exactly there`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 5)

            BooblikSubscriber(address, FAST).use { subscriber ->
                val batches =
                    subscriber
                        .replay(TOPIC, StartPosition.At(Offset(3)), listOf(PartitionId(0)))
                        .toList()
                assertEquals(Offset(3), batches.first().baseOffset)
                assertEquals(2, batches.sumOf { it.records.size })
            }
        }

    /** at-least-once, stated in the test because it is the whole semantics of `checkpointing`. */
    @Test
    fun `a checkpoint is not saved when handling the batch throws`() =
        withBroker { address, produce ->
            produce(PartitionId(0), 2)

            val saved = mutableListOf<Offset>()
            val store =
                object : OffsetStore {
                    override suspend fun load(
                        topic: TopicName,
                        partition: PartitionId,
                    ): Offset? = null

                    override suspend fun save(
                        topic: TopicName,
                        partition: PartitionId,
                        offset: Offset,
                    ) {
                        saved += offset
                    }
                }

            BooblikSubscriber(address, FAST).use { subscriber ->
                assertFailsWith<IllegalStateException> {
                    subscriber
                        .replay(TOPIC, StartPosition.Earliest, listOf(PartitionId(0)))
                        .checkpointing(store)
                        .collect { error("handling failed") }
                }
            }
            assertContentEquals(emptyList(), saved, "a batch that was not handled must not move the position")
        }

    private companion object {
        val TOPIC = TopicName("orders")

        /** Short waits: these tests assert on behaviour, not on the thirty-second default. */
        val FAST = SubscriptionConfig(maxWaitMillis = 1_500)
    }

    /** Two partitions, so "covers every partition" can mean something. */
    private fun withBroker(body: suspend (InetSocketAddress, (PartitionId, Int) -> Unit) -> Unit) =
        runBlocking {
            val dir = Files.createTempDirectory("booblik-subscription")
            val scope = CoroutineScope(SupervisorJob())
            val entries =
                listOf(PartitionId(0), PartitionId(1)).map { partition ->
                    val log = PartitionLog.open(dir.resolve("orders-${partition.value}"), SegmentMode.FILE_CHANNEL)
                    PartitionRegistry.Key(TOPIC, partition) to PartitionHandle(log, PartitionWriter(log, scope))
                }
            val server =
                BooblikServer(
                    PartitionRegistry.of(*entries.toTypedArray()),
                    ServerConfig(port = 0, bindAddress = "127.0.0.1"),
                )
            try {
                val address = server.start()
                BooblikClient(address).use { producer ->
                    body(address) { partition, count ->
                        producer.sendProduce(TOPIC, partition, List(count) { ByteArray(16) { b -> b.toByte() } })
                        producer.receiveProduce()
                    }
                }
            } finally {
                server.close()
                scope.cancel()
                entries.forEach { it.second.log.close() }
                @OptIn(ExperimentalPathApi::class)
                dir.deleteRecursively()
            }
        }
}
