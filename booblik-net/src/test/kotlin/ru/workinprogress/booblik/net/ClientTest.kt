package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.booblik.net.client.FetchFailedException
import ru.workinprogress.booblik.net.client.ProduceFailedException
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.ProducerConfig
import ru.workinprogress.booblik.net.wire.ErrorCode
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** M-40/M-41: the pipelined connection, the accumulating producer, the offset-tracking consumer. */
class ClientTest {
    private val topic = TopicName("orders")
    private val partition = PartitionId(0)

    private fun withClient(
        segmentCapacity: Int = 1 shl 20,
        body: suspend CoroutineScope.(BooblikConnection, InetSocketAddress) -> Unit,
    ) {
        val dir = Files.createTempDirectory("booblik-client")
        val broker =
            Broker.open(dir, mapOf(topic to 1), BrokerConfig(segmentCapacity = segmentCapacity))
        val server = BooblikServer(broker.registry, ServerConfig(port = 0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val address = server.start()
            BooblikConnection(address, scope).use { connection ->
                runBlocking { body(connection, address) }
            }
        } finally {
            scope.cancel()
            server.close()
            broker.close()
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `many requests in flight get their own answers back`() {
        // The point of correlation ids. Fifty callers await concurrently on one socket; each must
        // get the offset of its own batch and not somebody else's.
        withClient { connection, _ ->
            val results =
                (0 until 50)
                    .map { i ->
                        async(Dispatchers.Default) {
                            connection.produce(topic, partition, listOf("batch-$i".toByteArray()))!!
                        }
                    }.awaitAll()

            assertEquals(50, results.size)
            assertEquals((1..50).toList(), results.map { it.correlationId }.sorted())
            assertEquals((0L until 50L).toList(), results.map { it.baseOffset.value }.sorted())
        }
    }

    @Test
    fun `a producer batches records instead of sending them one at a time`() {
        // Observable from the offsets: a hundred records that arrived as one batch share a base
        // offset run with no gaps, and the log ends exactly where they end.
        withClient { connection, _ ->
            val producer = Producer(connection, this, ProducerConfig(maxBatchSize = 100, lingerMillis = 50))
            val handles = (0 until 100).map { producer.send(topic, partition, "r-$it".toByteArray()) }
            producer.flush()

            val offsets = handles.map { it.await().value }
            assertEquals((0L until 100L).toList(), offsets)
            producer.close()
        }
    }

    @Test
    fun `linger bounds how long a lone record waits`() {
        // A single record has nobody to batch with, so it must go on the timer rather than sit
        // there until a hundredth record shows up.
        withClient { connection, _ ->
            val producer = Producer(connection, this, ProducerConfig(maxBatchSize = 1000, lingerMillis = 20))
            val handle = producer.send(topic, partition, "alone".toByteArray())
            assertEquals(Offset.ZERO, handle.await())
            producer.close()
        }
    }

    @Test
    fun `a producer keeps partitions apart`() {
        val dir = Files.createTempDirectory("booblik-client-multi")
        val broker = Broker.open(dir, mapOf(topic to 2))
        val server = BooblikServer(broker.registry, ServerConfig(port = 0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            BooblikConnection(server.start(), scope).use { connection ->
                runBlocking {
                    val producer = Producer(connection, scope, ProducerConfig(maxBatchSize = 10, lingerMillis = 20))
                    val zero = (0 until 5).map { producer.send(topic, PartitionId(0), "p0-$it".toByteArray()) }
                    val one = (0 until 5).map { producer.send(topic, PartitionId(1), "p1-$it".toByteArray()) }
                    producer.flush()

                    // Both runs start at zero: separate partitions, separate offset spaces.
                    assertEquals((0L until 5L).toList(), zero.map { it.await().value })
                    assertEquals((0L until 5L).toList(), one.map { it.await().value })
                    producer.close()
                }
            }
        } finally {
            scope.cancel()
            server.close()
            broker.close()
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a consumer reads forward and remembers where it stopped`() {
        withClient { connection, _ ->
            connection.produce(topic, partition, (0 until 20).map { "record-$it".toByteArray() })

            val consumer = Consumer(connection, topic, partition)
            val first = consumer.poll()
            assertEquals(20, first.records.size)
            assertEquals(Offset(20), consumer.position)
            assertContentEquals("record-0".toByteArray(), first.records.first())

            // Caught up: an empty batch, not an error. This is the steady state of a consumer.
            val second = consumer.poll()
            assertTrue(second.isEmpty)
            assertEquals(Offset(20), consumer.position)
            assertTrue(consumer.isCaughtUp())

            consumer.seek(Offset(5))
            assertContentEquals("record-5".toByteArray(), consumer.poll().records.first())
        }
    }

    @Test
    fun `a consumer advances only past whole records when a response is cut`() {
        // maxBytes cuts on a byte boundary. The partial tail is dropped, and the next poll asks for
        // that record again from its start — so nothing is skipped and nothing is duplicated.
        withClient { connection, _ ->
            val records = (0 until 10).map { ByteArray(100) { b -> (it + b).toByte() } }
            connection.produce(topic, partition, records)

            val consumer = Consumer(connection, topic, partition, maxBytes = 270)
            val first = consumer.poll()
            assertEquals(2, first.records.size, "270 bytes holds two 108-byte records and part of a third")
            assertEquals(Offset(2), consumer.position)

            val second = consumer.poll()
            assertContentEquals(records[2], second.records.first(), "the cut record comes back whole")
        }
    }

    @Test
    fun `broker errors reach the caller as exceptions`() {
        withClient(segmentCapacity = 1024) { connection, _ ->
            val producer = Producer(connection, this, ProducerConfig(maxBatchSize = 1, lingerMillis = 0))
            val failure =
                assertFailsWith<ProduceFailedException> {
                    producer.send(topic, partition, ByteArray(2000)).await()
                }
            assertEquals(ErrorCode.RECORD_TOO_LARGE, failure.code)
            producer.close()

            val consumer = Consumer(connection, topic, partition, startOffset = Offset(999))
            assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, assertFailsWith<FetchFailedException> { consumer.poll() }.code)
        }
    }

    @Test
    fun `AckPolicy NONE returns nothing and still writes`() {
        withClient { connection, _ ->
            assertEquals(null, connection.produce(topic, partition, listOf("quiet".toByteArray()), AckPolicy.NONE))
            // Proved by the next request: the write happened, so this one lands at offset 1.
            assertEquals(Offset(1), connection.produce(topic, partition, listOf("loud".toByteArray()))!!.baseOffset)
        }
    }
}
