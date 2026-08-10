package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.wire.ErrorCode
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** M-42: several topics, several partitions, and what each of them does and does not share. */
class BrokerTest {
    private val orders = TopicName("orders")
    private val clicks = TopicName("clicks")

    private fun withBroker(body: (Broker, InetSocketAddress) -> Unit) {
        val dir = Files.createTempDirectory("booblik-broker")
        val broker = Broker.open(dir, mapOf(orders to 3, clicks to 2))
        val server = BooblikServer(broker.registry, ServerConfig(port = 0))
        try {
            body(broker, server.start())
        } finally {
            server.close()
            broker.close()
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `each partition gets its own directory`() {
        val dir = Files.createTempDirectory("booblik-broker-layout")
        try {
            Broker.open(dir, mapOf(orders to 3, clicks to 2)).use { broker ->
                assertEquals(5, broker.partitions.size)
                assertEquals(
                    listOf("clicks-0", "clicks-1", "orders-0", "orders-1", "orders-2"),
                    dir.listDirectoryEntries().map { it.name }.sorted(),
                )
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `partitions have independent offsets`() {
        // Both start at zero and neither knows the other exists. An offset shared across partitions
        // would need something to assign it, and that something would be the end of one writer per
        // partition.
        withBroker { _, address ->
            BooblikClient(address).use { client ->
                client.sendProduce(orders, PartitionId(0), listOf("a".toByteArray(), "b".toByteArray()))
                val first = client.receiveProduce()
                client.sendProduce(orders, PartitionId(1), listOf("c".toByteArray()))
                val second = client.receiveProduce()
                client.sendProduce(clicks, PartitionId(0), listOf("d".toByteArray()))
                val third = client.receiveProduce()

                assertEquals(Offset.ZERO, first.baseOffset)
                assertEquals(Offset.ZERO, second.baseOffset, "another partition of the same topic")
                assertEquals(Offset.ZERO, third.baseOffset, "another topic entirely")
                assertEquals(Offset(2), first.logEndOffset)
                assertEquals(Offset(1), second.logEndOffset)
            }
        }
    }

    @Test
    fun `records land in the partition they were addressed to`() {
        withBroker { _, address ->
            BooblikClient(address).use { client ->
                client.sendProduce(orders, PartitionId(2), listOf("into two".toByteArray()))
                client.receiveProduce()

                client.sendFetch(orders, PartitionId(2), Offset.ZERO, 1024)
                assertContentEquals("into two".toByteArray(), client.receiveFetch().records.single())

                client.sendFetch(orders, PartitionId(0), Offset.ZERO, 1024)
                assertEquals(emptyList(), client.receiveFetch().records, "a sibling partition stayed empty")
            }
        }
    }

    @Test
    fun `a partition beyond the configured count is unknown`() {
        withBroker { _, address ->
            BooblikClient(address).use { client ->
                client.sendFetch(orders, PartitionId(3), Offset.ZERO, 1024)
                assertEquals(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION, client.receiveFetch().error)
            }
        }
    }

    @Test
    fun `a reopened broker recovers every partition`() {
        val dir = Files.createTempDirectory("booblik-broker-restart")
        try {
            Broker.open(dir, mapOf(orders to 2)).use { broker ->
                val server = BooblikServer(broker.registry, ServerConfig(port = 0))
                try {
                    BooblikClient(server.start()).use { client ->
                        client.sendProduce(orders, PartitionId(0), listOf("kept".toByteArray()))
                        client.receiveProduce()
                        client.sendProduce(orders, PartitionId(1), listOf("also kept".toByteArray()))
                        client.receiveProduce()
                    }
                } finally {
                    server.close()
                }
            }

            Broker.open(dir, mapOf(orders to 2)).use { broker ->
                val server = BooblikServer(broker.registry, ServerConfig(port = 0))
                try {
                    BooblikClient(server.start()).use { client ->
                        client.sendFetch(orders, PartitionId(0), Offset.ZERO, 1024)
                        assertContentEquals("kept".toByteArray(), client.receiveFetch().records.single())
                        client.sendFetch(orders, PartitionId(1), Offset.ZERO, 1024)
                        assertContentEquals("also kept".toByteArray(), client.receiveFetch().records.single())
                    }
                } finally {
                    server.close()
                }
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }
}
