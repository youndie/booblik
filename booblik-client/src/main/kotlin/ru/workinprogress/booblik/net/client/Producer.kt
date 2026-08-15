package ru.workinprogress.booblik.net.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.ErrorCode

class ProduceFailedException(
    val code: ErrorCode,
) : IllegalStateException("broker refused the record: $code")

data class ProducerConfig(
    /** Records per request. Reached first, the batch goes immediately. */
    val maxBatchSize: Int = 100,
    /**
     * How long an incomplete batch waits for company, in milliseconds.
     *
     * Zero is not the fast setting. It sends every record on its own, which M-14 measured at
     * 80 592 records per second against 4 335 482 for batches of a hundred — the accumulator gives
     * up the single largest performance factor in this project. Non-zero trades a bounded amount of
     * latency for that factor.
     */
    val lingerMillis: Long = 5,
    val ackPolicy: AckPolicy = AckPolicy.WRITTEN,
)

/**
 * Accumulates records and sends them in batches.
 *
 * The whole point is the accumulator. M-14 found that the unit of a write is worth 54× — an order
 * of magnitude more than any other decision in this project, including the choice of write path —
 * so a client that sends records one at a time throws away most of the broker. Batching is
 * therefore not an optimisation to enable later; it is what a producer *is*.
 *
 * ## The accumulation loop
 *
 * One coroutine owns the pending records, same shape as the broker's own writer. It takes the first
 * record, then keeps collecting until either the batch is full or [ProducerConfig.lingerMillis]
 * elapses **from the first record**, not from the last. Timing from the last would let a steady
 * trickle of records postpone a send indefinitely, which turns a latency bound into a latency hope.
 *
 * Records for different partitions accumulate separately and go out as separate requests: a request
 * addresses one partition, because a partition is what has one writer.
 */
class Producer(
    internal val connection: BooblikConnection,
    scope: CoroutineScope,
    private val config: ProducerConfig = ProducerConfig(),
) : AutoCloseable {
    private val mailbox = Channel<Command>(Channel.BUFFERED)

    private val job: Job =
        scope.launch {
            try {
                runLoop()
            } finally {
                drainPending()
            }
        }

    private val pending = LinkedHashMap<Key, Batch>()

    /**
     * Queues [record] and returns a handle that completes with the offset it got.
     *
     * The record is not on the wire when this returns — that is the point. Await the result to know
     * it landed, or [flush] to push everything queued.
     */
    suspend fun send(
        topic: TopicName,
        partition: PartitionId,
        record: ByteArray,
    ): CompletableDeferred<Offset> {
        val answer = CompletableDeferred<Offset>()
        mailbox.send(Command.Append(Key(topic, partition), record, answer))
        return answer
    }

    /**
     * A handle for [topic], with its partitions taken from the broker rather than from an argument.
     *
     * Asking is the point: a partition count passed in by hand can disagree with the broker, and
     * the result — records piling into some partitions while others are never written — reads as a
     * data problem rather than as the configuration mistake it is.
     */
    suspend fun topic(
        topic: TopicName,
        partitioner: Partitioner = Partitioner.Fnv1a,
    ): TopicHandle {
        val answer = connection.metadata(listOf(topic))
        if (answer.error != ErrorCode.NONE) throw ProduceFailedException(answer.error)
        val partitions =
            answer.topics
                .singleOrNull()
                ?.partitions
                ?.map { it.partition }
                .orEmpty()
        check(partitions.isNotEmpty()) { "broker has no partitions for ${topic.value}" }
        return TopicHandle(this, topic, partitions, partitioner)
    }

    /** Sends everything queued and waits for the broker to answer all of it. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        mailbox.send(Command.Flush(done))
        done.await()
    }

    override fun close() {
        mailbox.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runLoop() {
        for (first in mailbox) {
            when (first) {
                is Command.Flush -> {
                    sendAll()
                    first.done.complete(Unit)
                    continue
                }

                is Command.Append -> {
                    accumulate(first)
                }
            }

            // The window starts at the first record of the batch. Restarting it on every arrival
            // would let a steady stream defer the send forever.
            val deadline = System.nanoTime() + config.lingerMillis * 1_000_000
            while (!isAnyBatchFull()) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                // `select`, not `withTimeoutOrNull(mailbox.receive())`. A timeout **cancels** the
                // receive, and a cancelled receive can take an element off the channel and then
                // drop it — after which the caller waiting on that record's `CompletableDeferred`
                // waits for ever while the accumulator carries on serving everybody else.
                //
                // The broker's own writer had this exact bug and it is written up in
                // `PartitionWriter.awaitCommand`; the client kept it a year longer because nothing
                // drove two topics through one producer at different rates. The sample did, and the
                // publisher stopped after a single task while still sending events happily.
                //
                // `select` either takes the element or takes the timeout. Never both, never neither.
                val next =
                    select<Command?> {
                        mailbox.onReceiveCatching { it.getOrNull() }
                        onTimeout(remaining) { null }
                    } ?: break
                when (next) {
                    is Command.Append -> {
                        accumulate(next)
                    }

                    is Command.Flush -> {
                        sendAll()
                        next.done.complete(Unit)
                        break
                    }
                }
            }
            sendAll()
        }
    }

    private fun accumulate(command: Command.Append) {
        pending.getOrPut(command.key) { Batch() }.add(command.record, command.answer)
    }

    private fun isAnyBatchFull() = pending.values.any { it.records.size >= config.maxBatchSize }

    private suspend fun sendAll() {
        if (pending.isEmpty()) return
        // `map`, not `entries.toList()`. The latter hands back **views** into the map, and reading
        // `key` off one after `clear()` is undefined — Kotlin/Native's HashMap notices and throws
        // ConcurrentModificationException, while the JVM's LinkedHashMap does not check and returns
        // the stale key, so the same line worked here by accident of one implementation for as long
        // as there was only one. Found by the Kotlin/Native probe in M-134а.
        val batches = pending.map { (key, batch) -> key to batch }
        pending.clear()
        for ((key, batch) in batches) {
            deliver(key, batch)
        }
    }

    private suspend fun deliver(
        key: Key,
        batch: Batch,
    ) {
        try {
            val result = connection.produce(key.topic, key.partition, batch.records, config.ackPolicy)
            if (result == null) {
                // AckPolicy.NONE: nothing is coming back, and the caller was told as much by the
                // policy it chose. Completing with the offset we do not have would be a lie, so the
                // handle completes with the only honest thing available — the batch went out.
                batch.answers.forEach { it.complete(Offset.ZERO) }
                return
            }
            if (result.error != ErrorCode.NONE) {
                val failure = ProduceFailedException(result.error)
                batch.answers.forEach { it.completeExceptionally(failure) }
                return
            }
            // Offsets in a batch are consecutive, so one base offset answers every record in it.
            batch.answers.forEachIndexed { index, answer ->
                answer.complete(result.baseOffset + index.toLong())
            }
        } catch (e: Throwable) {
            batch.answers.forEach { it.completeExceptionally(e) }
        }
    }

    private fun drainPending() {
        val failure = ConnectionClosedException(null)
        pending.values.forEach { batch -> batch.answers.forEach { it.completeExceptionally(failure) } }
        pending.clear()
        while (true) {
            when (val left = mailbox.tryReceive().getOrNull()) {
                null -> return
                is Command.Append -> left.answer.completeExceptionally(failure)
                is Command.Flush -> left.done.complete(Unit)
            }
        }
    }

    private data class Key(
        val topic: TopicName,
        val partition: PartitionId,
    )

    private class Batch {
        val records = ArrayList<ByteArray>()
        val answers = ArrayList<CompletableDeferred<Offset>>()

        fun add(
            record: ByteArray,
            answer: CompletableDeferred<Offset>,
        ) {
            records += record
            answers += answer
        }
    }

    private sealed interface Command {
        class Append(
            val key: Key,
            val record: ByteArray,
            val answer: CompletableDeferred<Offset>,
        ) : Command

        class Flush(
            val done: CompletableDeferred<Unit>,
        ) : Command
    }
}
