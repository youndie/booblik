package ru.workinprogress.booblik.net.client

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.RequestEncoder
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * The low-level client: one socket, blocking, no bookkeeping.
 *
 * Sending and receiving are separate calls so that requests can be pipelined by hand — which is
 * what the load harness wants, and what a test that needs to prove response *ordering* needs.
 * Anything wanting a producer or a consumer should use [Producer] and [Consumer] instead; this is
 * the layer they are built on.
 */
class BooblikClient(
    address: InetSocketAddress,
) : Closeable {
    private val channel =
        SocketChannel.open(address).apply {
            setOption(StandardSocketOptions.TCP_NODELAY, true)
        }

    private var nextCorrelationId = 1

    /** Queues a PRODUCE. Returns the correlation id, or null with [AckPolicy.NONE] — no answer comes. */
    fun sendProduce(
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    ): Int? {
        val correlationId = nextCorrelationId++
        writeFully(RequestEncoder.produce(correlationId, topic, partition, records, ackPolicy))
        return if (ackPolicy == AckPolicy.NONE) null else correlationId
    }

    /** Queues a FETCH and returns its correlation id. */
    fun sendFetch(
        topic: TopicName,
        partition: PartitionId,
        fetchOffset: Offset,
        maxBytes: Int,
    ): Int {
        val correlationId = nextCorrelationId++
        writeFully(RequestEncoder.fetch(correlationId, topic, partition, fetchOffset, maxBytes))
        return correlationId
    }

    fun receiveProduce(): ProduceResult = ResponseReader.produce(ResponseReader.readFrame(channel))

    fun receiveFetch(): FetchResult = ResponseReader.fetch(ResponseReader.readFrame(channel))

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close() {
        channel.close()
    }
}
