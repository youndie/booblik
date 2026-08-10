package ru.workinprogress.booblik.net.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Raised to every request still waiting when a connection dies. */
class ConnectionClosedException(
    cause: Throwable?,
) : IllegalStateException("connection to the broker was closed", cause)

/**
 * A pipelined connection: many requests in flight, answers matched back to their callers.
 *
 * ## How the matching works, and why it is a queue
 *
 * The broker answers a connection strictly in request order, so pending requests are a **FIFO
 * queue**, not a map. The correlation id is still checked against the head of that queue on every
 * response — a map would silently tolerate the broker reordering, and this instead fails loudly.
 * Ordering is a promise the protocol makes; a client that cannot notice it being broken is a client
 * that will one day deliver the wrong record to the wrong caller.
 *
 * ## Why one writer coroutine
 *
 * Several callers send concurrently, and a socket does not tolerate two interleaved writes. Rather
 * than a lock, requests go through a channel and one coroutine drains it — the same shape the
 * broker's own writer uses, for the same reason. It also gives the ordering the queue depends on
 * for free: the coroutine enqueues the pending entry and writes the bytes without anything running
 * in between.
 *
 * [AckPolicy.NONE] enqueues nothing, because nothing will come back.
 */
class BooblikConnection(
    address: InetSocketAddress,
    scope: CoroutineScope,
) : Closeable {
    private val socket =
        SocketChannel.open(address).apply {
            setOption(StandardSocketOptions.TCP_NODELAY, true)
        }

    private val outbound = Channel<Outgoing>(Channel.BUFFERED)
    private val pending = ConcurrentLinkedQueue<Pending>()

    @Volatile
    private var failure: Throwable? = null

    /**
     * Atomic, and it has to be: the whole point of this class is that several coroutines call it at
     * once. A plain `var i = 1; i++` handed two callers the same id, and the duplicate then matched
     * the wrong response to the wrong caller — the exact failure the correlation id exists to
     * prevent. Found by `ClientTest.many requests in flight get their own answers back`.
     */
    private val correlationIds = AtomicInteger(1)

    private val writer: Job =
        scope.launch(Dispatchers.IO) {
            try {
                for (message in outbound) {
                    // Registered before the bytes go out, and by the same coroutine, so the queue
                    // order and the wire order cannot disagree.
                    message.pending?.let(pending::add)
                    writeFully(message.frame)
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }

    private val reader: Job =
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val frame = ResponseReader.readFrame(socket)
                    val waiting = pending.poll() ?: error("broker sent a response nobody asked for")
                    waiting.complete(frame)
                }
            } catch (e: Throwable) {
                fail(e)
            }
        }

    suspend fun produce(
        topic: TopicName,
        partition: PartitionId,
        records: List<ByteArray>,
        ackPolicy: AckPolicy = AckPolicy.WRITTEN,
    ): ProduceResult? {
        val correlationId = correlationIds.getAndIncrement()
        val frame = RequestEncoder.produce(correlationId, topic, partition, records, ackPolicy)
        if (ackPolicy == AckPolicy.NONE) {
            outbound.send(Outgoing(frame, null))
            return null
        }
        val answer = CompletableDeferred<ProduceResult>()
        outbound.send(Outgoing(frame, Pending.Produce(correlationId, answer)))
        return answer.await()
    }

    suspend fun fetch(
        topic: TopicName,
        partition: PartitionId,
        fetchOffset: Offset,
        maxBytes: Int,
    ): FetchResult {
        val correlationId = correlationIds.getAndIncrement()
        val answer = CompletableDeferred<FetchResult>()
        outbound.send(
            Outgoing(
                RequestEncoder.fetch(correlationId, topic, partition, fetchOffset, maxBytes),
                Pending.Fetch(correlationId, answer),
            ),
        )
        return answer.await()
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) socket.write(buffer)
    }

    private fun fail(cause: Throwable) {
        if (failure == null) failure = cause
        outbound.close(cause)
        // Nobody is left to answer these, and a caller waiting on a dead connection waits forever.
        while (true) {
            val waiting = pending.poll() ?: break
            waiting.fail(ConnectionClosedException(cause))
        }
        runCatching { socket.close() }
    }

    override fun close() {
        outbound.close()
        writer.cancel()
        reader.cancel()
        fail(ConnectionClosedException(null))
    }

    private class Outgoing(
        val frame: ByteBuffer,
        val pending: Pending?,
    )

    private sealed class Pending(
        val correlationId: Int,
    ) {
        abstract fun complete(frame: ByteBuffer)

        abstract fun fail(cause: Throwable)

        protected fun checkOrder(actual: Int) {
            check(actual == correlationId) {
                "broker answered out of order: expected correlation id $correlationId, got $actual"
            }
        }

        class Produce(
            correlationId: Int,
            private val answer: CompletableDeferred<ProduceResult>,
        ) : Pending(correlationId) {
            override fun complete(frame: ByteBuffer) {
                val result = ResponseReader.produce(frame)
                checkOrder(result.correlationId)
                answer.complete(result)
            }

            override fun fail(cause: Throwable) {
                answer.completeExceptionally(cause)
            }
        }

        class Fetch(
            correlationId: Int,
            private val answer: CompletableDeferred<FetchResult>,
        ) : Pending(correlationId) {
            override fun complete(frame: ByteBuffer) {
                val result = ResponseReader.fetch(frame)
                checkOrder(result.correlationId)
                answer.complete(result)
            }

            override fun fail(cause: Throwable) {
                answer.completeExceptionally(cause)
            }
        }
    }
}
