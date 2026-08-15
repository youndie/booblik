package ru.workinprogress.booblik.native

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.wire.ErrorCode
import kotlin.time.TimeSource

/** The broker refused the batch this record was in. */
class ProduceFailedException(
    val code: ErrorCode,
) : IllegalStateException("booblik: broker refused the record: $code")

data class ProducerConfig(
    /** Records per request. Reached first, the batch goes at once. */
    val maxBatchSize: Int = 100,
    /**
     * How long an incomplete batch waits for company, in milliseconds.
     *
     * Zero is not the fast setting. It sends every record on its own, which the broker's own
     * measurements put at 80 592 records/s against 4 335 482 for batches of a hundred.
     */
    val lingerMillis: Long = 5,
    val ackPolicy: AckPolicy = AckPolicy.WRITTEN,
)

/**
 * Accumulates records and sends them in batches.
 *
 * ## Where it runs, and why that took a decision
 *
 * The loop and the socket both live on a thread this producer owns, from `newSingleThreadContext`.
 * That is not one option among several: on Kotlin/Native **`Dispatchers.IO` is `internal`** — the
 * compiler says so, the documentation says otherwise — so there is no IO pool for a blocking socket,
 * and `Dispatchers.Default` has as many threads as the machine has cores. Blocking one of those on
 * a network read is the thing not to do.
 *
 * **[close] must be called.** `newSingleThreadContext` is delicate API for exactly this reason: the
 * thread it starts is not reclaimed on its own, and a producer that is dropped rather than closed
 * leaks it.
 *
 * ## When to use it, measured
 *
 * **Do not await every record through it.** That is the one shape where it loses, and it loses
 * badly: 140 records/s against 595 for the same records sent directly (measurement 23). The reason
 * is arithmetic rather than tuning — a caller waiting for its own offset before sending the next
 * means a batch can never hold more records than there are callers, so the window is paid for a
 * batch that was already as full as it was ever going to get. That is the group-commit finding of
 * measurement 22 in a second place.
 *
 * **Use it when you do not await each record.** There it buys something nothing else here can:
 * **21 692 records/s against 595**, thirty-six times, because [BooblikConnection] is blocking and
 * cannot pipeline — without an accumulator there is no way to have a second record in flight at
 * all. The advantage narrows as callers multiply (2.2× at eight, 1.1× at sixty-four), and what
 * catches up is connections rather than batching: a direct caller has one each and this has one
 * for everybody.
 *
 * If you await every offset, [BooblikConnection.produce] with a list is the better call.
 *
 * Records for different partitions accumulate separately and go out as separate requests, because
 * a request addresses one partition — a partition being what has one writer.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Producer(
    private val connection: BooblikConnection,
    scope: CoroutineScope,
    private val config: ProducerConfig = ProducerConfig(),
) : AutoCloseable {
    private val dispatcher = newSingleThreadContext("booblik-producer")
    private val mailbox = Channel<Command>(Channel.BUFFERED)

    @Suppress("unused")
    private val job: Job =
        scope.launch(dispatcher) {
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
    ): CompletableDeferred<Offset?> {
        val answer = CompletableDeferred<Offset?>()
        mailbox.send(Command.Append(Key(topic, partition), record, answer))
        return answer
    }

    /** Sends everything queued and waits for the broker to answer all of it. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        mailbox.send(Command.Flush(done))
        done.await()
    }

    /**
     * Stops the accumulator and releases its thread.
     *
     * Not optional. The thread from `newSingleThreadContext` outlives a dropped producer, and a
     * program that creates one per request would run out of threads rather than slow down.
     */
    override fun close() {
        mailbox.close()
        dispatcher.close()
    }

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
            // would let a steady stream defer the send for ever, turning a latency bound into a
            // latency hope.
            val started = TimeSource.Monotonic.markNow()
            while (!isAnyBatchFull()) {
                val remaining = config.lingerMillis - started.elapsedNow().inWholeMilliseconds
                if (remaining <= 0) break

                // `select`, and **not** `withTimeoutOrNull(mailbox.receive())`. A timeout cancels
                // the receive, and a cancelled receive can take an element off the channel and then
                // drop it — after which the caller waiting on that record waits for ever while the
                // accumulator carries on serving everybody else. The broker's own writer had this
                // bug, and so did the JVM client, twice. `select` either takes the element or takes
                // the timeout; never both, never neither.
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

    private fun sendAll() {
        if (pending.isEmpty()) return
        // `map`, not `entries.toList()`. The latter hands back **views** into the map, and reading
        // `key` off one after `clear()` is undefined — Kotlin/Native's HashMap notices and throws
        // ConcurrentModificationException, while the JVM's LinkedHashMap does not check and returns
        // the stale key, so the same line worked here by accident of one implementation for as long
        // as there was only one. Found by the Kotlin/Native probe in M-134а.
        val batches = pending.map { (key, batch) -> key to batch }
        pending.clear()
        for ((key, batch) in batches) deliver(key, batch)
    }

    private fun deliver(
        key: Key,
        batch: Batch,
    ) {
        val result =
            try {
                connection.produce(key.topic, key.partition, batch.records, config.ackPolicy)
            } catch (failure: Throwable) {
                // A batch that fails fails for all of its records. Completing some and abandoning
                // the rest would leave callers awaiting a result nothing will ever deliver.
                batch.answers.forEach { it.completeExceptionally(failure) }
                return
            }

        if (result == null) {
            // AckPolicy.NONE: sent, and there is no offset to report. Null and not a placeholder —
            // `Offset` refuses negative values, so the other clients' -1 has no equivalent here, and
            // completing with zero would hand back something indistinguishable from the first
            // record of a topic.
            batch.answers.forEach { it.complete(null) }
            return
        }
        if (result.error != ErrorCode.NONE) {
            batch.answers.forEach { it.completeExceptionally(ProduceFailedException(result.error)) }
            return
        }
        // One request is written by one call, so the records are contiguous from the base.
        batch.answers.forEachIndexed { index, answer -> answer.complete(result.baseOffset + index.toLong()) }
    }

    /** Whatever was queued when the mailbox closed still goes out; dropping it would be silent loss. */
    private fun drainPending() {
        sendAll()
        while (true) {
            val leftover = mailbox.tryReceive().getOrNull() ?: break
            when (leftover) {
                is Command.Append -> {
                    leftover.answer.completeExceptionally(
                        IllegalStateException("booblik: producer is closed"),
                    )
                }

                is Command.Flush -> {
                    leftover.done.complete(Unit)
                }
            }
        }
    }

    private data class Key(
        val topic: TopicName,
        val partition: PartitionId,
    )

    private class Batch {
        val records = ArrayList<ByteArray>()
        val answers = ArrayList<CompletableDeferred<Offset?>>()

        fun add(
            record: ByteArray,
            answer: CompletableDeferred<Offset?>,
        ) {
            records += record
            answers += answer
        }
    }

    private sealed interface Command {
        class Append(
            val key: Key,
            val record: ByteArray,
            val answer: CompletableDeferred<Offset?>,
        ) : Command

        class Flush(
            val done: CompletableDeferred<Unit>,
        ) : Command
    }
}
