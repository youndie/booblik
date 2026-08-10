package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Partitioner
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.batch
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M-73: the topic handle and the single-request batch. */
class PublishingTest {
    @Test
    fun `a handle takes its partitions from the broker`() =
        withProducer { producer, _ ->
            val orders = producer.topic(TOPIC)
            assertEquals(listOf(PartitionId(0), PartitionId(1), PartitionId(2)), orders.partitions)
        }

    /** The same key must always choose the same partition, or a key is worth nothing. */
    @Test
    fun `the same key always picks the same partition`() =
        withProducer { producer, _ ->
            val orders = producer.topic(TOPIC)
            val key = "user-42".toByteArray()
            val chosen = orders.partitionFor(key)
            repeat(20) { assertEquals(chosen, orders.partitionFor(key)) }
            assertTrue(chosen in orders.partitions)
        }

    @Test
    fun `without a key partitions are used round-robin`() =
        withProducer { producer, _ ->
            val orders = producer.topic(TOPIC)
            val chosen = List(6) { orders.partitionFor(null) }
            assertEquals(
                listOf(PartitionId(0), PartitionId(1), PartitionId(2), PartitionId(0), PartitionId(1), PartitionId(2)),
                chosen,
                "an even spread is what somebody sending a handful of unkeyed records expects to see",
            )
        }

    /** A partitioner that always answers zero proves the handle actually uses the one it is given. */
    @Test
    fun `the partitioner is the one that was supplied`() =
        withProducer { producer, _ ->
            val orders = producer.topic(TOPIC, Partitioner { _, _ -> 2 })
            assertEquals(PartitionId(2), orders.partitionFor("anything".toByteArray()))
        }

    /**
     * The guarantee `batch { }` exists for: one request, offsets with nothing in between.
     *
     * Contiguous, **not** atomic — that distinction lives in the KDoc and in the docs, because it
     * cannot be tested without killing the process mid-write, which `CrashRecoveryTest` already
     * does for the storage layer.
     */
    @Test
    fun `a batch lands contiguously and reports every offset`() =
        withProducer { producer, address ->
            val offsets =
                producer.batch(TOPIC, PartitionId(0)) {
                    +"one".toByteArray()
                    +"two".toByteArray()
                    add("three".toByteArray())
                }

            assertEquals(listOf(Offset(0), Offset(1), Offset(2)), offsets)

            // Read back over the wire: the offsets are not just what the client computed.
            BooblikConnection(address, CoroutineScope(SupervisorJob())).use { connection ->
                val answer = connection.fetch(TOPIC, PartitionId(0), Offset.ZERO, maxBytes = 1 shl 20)
                assertEquals(
                    listOf("one", "two", "three"),
                    answer.records.map { String(it) },
                    "one request, in order, with nothing interleaved",
                )
            }
        }

    @Test
    fun `an empty batch sends nothing`() =
        withProducer { producer, _ ->
            assertEquals(emptyList(), producer.batch(TOPIC, PartitionId(0)) { })
        }

    private companion object {
        val TOPIC = TopicName("orders")
    }

    private fun withProducer(body: suspend (Producer, InetSocketAddress) -> Unit) =
        runBlocking {
            val dir = Files.createTempDirectory("booblik-publishing")
            val scope = CoroutineScope(SupervisorJob())
            val entries =
                listOf(PartitionId(0), PartitionId(1), PartitionId(2)).map { partition ->
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
                BooblikConnection(address, scope).use { connection ->
                    Producer(connection, scope).use { producer -> body(producer, address) }
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
