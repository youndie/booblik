package ru.workinprogress.booblik.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.storage.Log

/**
 * The single coroutine that owns a partition's write side.
 *
 * Ordering and exclusivity come from ownership, not from a lock: only this coroutine ever touches
 * the [Log], so there is nothing to contend for. `booblik-core` contains no `Mutex` and no
 * `synchronized` — today that is a property of the code that someone has to keep checking by
 * reading it; M-62 turns it into a build failure.
 *
 * ## Why the unit is a batch
 *
 * The original design draft made the unit a single record: every `append` allocated a
 * `CompletableDeferred`, sent it through a channel, and suspended until the actor answered. Two
 * allocations and two context switches to guard a write that costs tens of nanoseconds. Here one
 * mailbox message carries a whole batch, so that overhead is divided by the batch size — the same
 * reason Kafka's unit on the wire is a record batch and not a record.
 *
 * ## Group commit
 *
 * The loop does not process one message at a time. It takes the first one, then drains whatever
 * else is already queued without suspending, writes all of it, and calls [Log.force] **once** if
 * anybody in that group asked for [AckPolicy.FORCED].
 *
 * This is what makes durable writes usable at all. A disk barrier costs about 4 ms on the
 * reference host, so a producer that forces every batch is capped near 250 batches per second —
 * and that cap is per *barrier*, not per producer. With group commit, a hundred producers waiting
 * on the same barrier pay for one, so the cap becomes a floor on latency instead of a ceiling on
 * throughput. Measured in `GroupCommitBenchmark`.
 */
class PartitionWriter(
    private val log: Log,
    scope: CoroutineScope,
    mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
    private val flushPolicy: FlushPolicy = FlushPolicy.Disabled,
) {
    private val mailbox = Channel<WriteCommand>(mailboxCapacity)

    /**
     * Touched only by the writer coroutine, so plain fields are correct and free. `queued` is the
     * exception — producers increment it, and it is what tells an operator the broker is behind.
     */
    private var recordsSinceFlush = 0L
    private var lastFlushNanos = System.nanoTime()

    private val queued =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    /** Batches accepted but not yet written. The one number that says "the broker is behind". */
    val mailboxDepth: Int get() = queued.get()

    private val watermark = MutableStateFlow(log.nextOffset)

    /**
     * The end of the log, published so a reader can wait for it to move instead of asking again.
     *
     * `StateFlow` and not `SharedFlow`, and the difference is not stylistic. A waiter reads the
     * current watermark, decides there is nothing to read, and only then starts listening; with a
     * `SharedFlow` an update landing inside that window is gone, and a lost wakeup presents as a
     * consumer that hangs once a day. A `StateFlow` holds its current value, so
     * `first { it > position }` cannot miss what already happened. Conflation is harmless here
     * because the watermark only moves forward and only the latest value means anything.
     *
     * Published once per committed group rather than per record — the group is what makes the
     * records visible, and per record would be a write on the hot path for no extra information.
     */
    val highWatermark: StateFlow<Offset> get() = watermark

    // Written by the writer coroutine and nobody else, so an ordinary field is both correct and
    // free; `@Volatile` is only so a reporter on another thread sees a recent value. A counter on
    // the hot path has to cost nothing, and this costs an increment.
    @Volatile
    var recordsWritten: Long = 0L
        private set

    @Volatile
    var bytesWritten: Long = 0L
        private set

    @Volatile
    var flushes: Long = 0L
        private set

    private val job: Job =
        scope.launch {
            try {
                runLoop()
            } finally {
                // Whoever is still waiting must be told, or they wait forever. Cancellation of the
                // scope is the ordinary way this coroutine ends, and it must not strand producers.
                mailbox.close()
                for (pending in generateSequence { mailbox.tryReceive().getOrNull() }) {
                    pending.ack?.completeExceptionally(WriterClosedException())
                }
            }
        }

    /**
     * Appends [records] as one unit and returns the offset of the first of them; the rest follow
     * consecutively. Suspends until [policy] is satisfied.
     *
     * With [AckPolicy.NONE] this returns `null` immediately after the batch is queued — there is
     * no reply to wait for, and no offset can be reported, because the offset does not exist until
     * the actor gets to it. That is the honest shape of fire-and-forget: a "promised" offset would
     * be a number the caller could compare against nothing.
     */
    suspend fun append(
        records: List<ByteArray>,
        policy: AckPolicy = AckPolicy.WRITTEN,
    ): Offset? {
        require(records.isNotEmpty()) { "an empty batch has no base offset to report" }
        if (policy == AckPolicy.NONE) {
            queued.incrementAndGet()
            mailbox.send(WriteCommand(records, policy, ack = null))
            return null
        }
        val ack = CompletableDeferred<Offset>()
        queued.incrementAndGet()
        mailbox.send(WriteCommand(records, policy, ack))
        return ack.await()
    }

    /** Convenience for the common single-record case. Still goes through the batch path. */
    suspend fun append(
        record: ByteArray,
        policy: AckPolicy = AckPolicy.WRITTEN,
    ): Offset? = append(listOf(record), policy)

    /** Stops accepting new batches and waits for everything already queued to be written. */
    suspend fun close() {
        mailbox.close()
        job.join()
    }

    private suspend fun runLoop() {
        val group = ArrayList<WriteCommand>()
        while (true) {
            val first = awaitCommand() ?: break
            group.add(first)
            // Drain without suspending. Everything already in the mailbox joins this group and
            // shares one barrier; anything that arrives later waits for the next round. No timer,
            // no configured window: the group size self-adjusts to the load, because a slow
            // barrier lets more messages accumulate behind it.
            while (true) {
                val more = mailbox.tryReceive().getOrNull() ?: break
                group.add(more)
            }

            var needsForce = false
            for (command in group) {
                command.baseOffset = writeBatch(command.records)
                recordsSinceFlush += command.records.size
                if (command.policy == AckPolicy.FORCED) needsForce = true
            }
            if (needsForce || countTriggerReached()) flushNow()

            // Acknowledged only after the barrier, and only after every write in the group — a
            // producer that hears "written" must not be able to observe a log that disagrees.
            for (command in group) {
                command.ack?.complete(command.baseOffset!!)
            }
            queued.addAndGet(-group.size)
            group.clear()
            // After the acks, not before: a reader woken by this must find the records already
            // readable, and `Log.nextOffset` is what makes them so.
            watermark.value = log.nextOffset
        }
    }

    /**
     * Waits for the next batch, forcing on the way if the time trigger comes due first.
     *
     * The time trigger only means anything here — waiting inside the receive rather than checking
     * after one. An idle broker never reaches the check, and the records right before the traffic
     * stopped are exactly the ones a time-based policy is supposed to cover.
     *
     * Returns null when the mailbox is closed, which ends the loop.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitCommand(): WriteCommand? {
        while (true) {
            val wait = millisUntilTimeTrigger()
            if (wait == null) return mailbox.receiveCatching().getOrNull()

            // `select`, not `withTimeoutOrNull(mailbox.receive())`, and the difference is not
            // stylistic. A timeout **cancels** the receive, and cancelling a receive can take an
            // element off the channel and then drop it — the producer waiting for that batch waits
            // forever. Against the real broker it showed up as `backlog 1` in the metrics line and
            // a client that never got its acknowledgement.
            //
            // `select` either takes the element or takes the timeout. Never both, never neither.
            val received =
                select {
                    mailbox.onReceiveCatching { it }
                    onTimeout(wait) { null }
                }
            if (received == null) {
                flushNow()
                continue
            }
            return received.getOrNull() ?: return null
        }
    }

    /** Milliseconds until the time trigger is due, or null if there is nothing to flush or no trigger. */
    private fun millisUntilTimeTrigger(): Long? {
        val every = flushPolicy.everyMillis ?: return null
        if (recordsSinceFlush == 0L) return null
        val due = lastFlushNanos + every * 1_000_000
        return ((due - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
    }

    private fun countTriggerReached(): Boolean {
        val every = flushPolicy.everyRecords ?: return false
        return recordsSinceFlush >= every
    }

    private fun flushNow() {
        if (recordsSinceFlush == 0L && flushPolicy.isEnabled) return
        log.force()
        flushes += 1
        recordsSinceFlush = 0
        lastFlushNanos = System.nanoTime()
    }

    private fun writeBatch(records: List<ByteArray>): Offset {
        val base = log.nextOffset
        var bytes = 0L
        for (record in records) {
            log.append(record)
            bytes += record.size
        }
        recordsWritten += records.size
        bytesWritten += bytes
        return base
    }

    private class WriteCommand(
        val records: List<ByteArray>,
        val policy: AckPolicy,
        val ack: CompletableDeferred<Offset>?,
    ) {
        var baseOffset: Offset? = null
    }

    companion object {
        /**
         * Deep enough that a burst does not immediately block producers, shallow enough that the
         * backlog stays bounded. Once it is full, `send` suspends — which is the correct
         * backpressure: the alternative is an unbounded mailbox, where overload turns into an
         * OutOfMemoryError instead of a slowdown. At a 64 MiB heap that is not a hypothetical.
         */
        const val DEFAULT_MAILBOX_CAPACITY = 1024
    }
}

/** Thrown to producers still waiting when the writer's scope is cancelled. */
class WriterClosedException : IllegalStateException("partition writer was closed before the batch was written")
