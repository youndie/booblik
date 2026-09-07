package io.github.youndie.booblik.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.storage.Log

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
public class PartitionWriter(
    private val log: Log,
    private val scope: CoroutineScope,
    private val mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
    private val flushPolicy: FlushPolicy = FlushPolicy.Disabled,
    /**
     * How long a group may wait for company before the barrier runs, in milliseconds.
     *
     * Zero — the default — is the behaviour this writer has always had: a group is whatever is
     * already in the mailbox, and nobody waits. A non-zero window trades a bounded amount of
     * latency for a larger group, which matters only under [AckPolicy.FORCED], where the barrier is
     * the cost and everyone in the group pays for one.
     *
     * Opening this as a knob rather than choosing a default is deliberate: which side of that trade
     * is right depends on the number of producers a deployment actually has, and that is not
     * something the broker can know. What it costs is measured (замер 22).
     */
    private val groupWindowMillis: Long = 0,
    /**
     * How long a failed writer waits before letting a producer try to bring it back.
     *
     * Five seconds rather than zero: while the volume is still full every attempt costs a fault,
     * so the retry rate has to be a constant and not a multiple of the request rate. Five rather
     * than a minute because the thing on the other side of it is an operator who has just freed
     * space and is watching to see whether writes come back.
     */
    private val resumeCooldownMillis: Long = 5_000,
) {
    // Replaced on resume rather than reopened: a closed `Channel` stays closed, so coming back from
    // a fault means a new one. Volatile because a producer reads it on another thread; every use
    // takes a local copy first, so a swap under a caller cannot leave it half-looking at two.
    @Volatile
    private var mailbox = Channel<WriteCommand>(mailboxCapacity)

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
    public val mailboxDepth: Int get() = queued.get()

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
    public val highWatermark: StateFlow<Offset> get() = watermark

    // Written by the writer coroutine and nobody else, so an ordinary field is both correct and
    // free; `@Volatile` is only so a reporter on another thread sees a recent value. A counter on
    // the hot path has to cost nothing, and this costs an increment.
    @Volatile
    public var recordsWritten: Long = 0L
        private set

    @Volatile
    public var bytesWritten: Long = 0L
        private set

    @Volatile
    public var flushes: Long = 0L
        private set

    /**
     * The batch this writer is in the middle of, and the reason it is a field.
     *
     * Everything else the loop touches is a local, which is correct because only the writer
     * coroutine touches it. This one has to be visible to the `finally` below: a command taken out
     * of the mailbox is no longer in the mailbox, so draining the mailbox does not reach it, and a
     * producer waiting on it waits for ever. That is exactly what a full volume produced —
     * `backlog 1`, `errors 0`, and a request that never came back (issue #15).
     */
    private var inFlight: List<WriteCommand> = emptyList()

    /**
     * Why this writer stopped, or null while it is running.
     *
     * Set by the loop on the way out and cleared by [tryResume]. While it is set, [append] refuses
     * immediately instead of blocking on a closed channel, and the session turns that refusal into
     * an error code the producer can read.
     */
    @Volatile
    public var failure: Throwable? = null
        private set

    /**
     * Single-flight guard for [tryResume]. A `compareAndSet` rather than a lock, because
     * `booblik-core` has neither `synchronized` nor `Mutex` and `NoLocksTest` fails the build over
     * either — see M-62.
     */
    private val resuming =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @Volatile
    private var lastResumeNanos = 0L

    /** Set by [close], so a writer on the way down is never brought back by a late producer. */
    @Volatile
    private var closed = false

    @Volatile
    private var job: Job = launchLoop()

    private fun launchLoop(): Job =
        scope.launch {
            try {
                runLoop()
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                throw cancellation
            } catch (broken: Throwable) {
                // This loop cannot carry on — the volume is full, or the mapping faulted, and the
                // next batch in this group would fault the same way. What it must not do is
                // disappear quietly: the exception is kept so that every producer after this one
                // gets an answer rather than a dropped connection, and so that [tryResume] knows
                // there is something to come back from.
                failure = broken
                throw broken
            } finally {
                // Whoever is still waiting must be told, or they wait forever. Cancellation of the
                // scope is the ordinary way this coroutine ends, and it must not strand producers.
                mailbox.close()
                val why = failure?.let { WriterFailedException(it) } ?: WriterClosedException()
                // Counted down **before** anyone is woken, and that order is the whole point: the
                // completion is what releases the producer, and a producer that has its answer is
                // free to read the backlog on the very next instruction. Waking first left a window
                // in which a refused batch was still counted as queued — small, real, and exactly
                // the number this failure made undiagnosable in the first place (issue #15).
                val stranded = inFlight
                queued.addAndGet(-stranded.size)
                inFlight = emptyList()
                for (pending in stranded) {
                    pending.ack?.completeExceptionally(why)
                }
                for (pending in generateSequence { mailbox.tryReceive().getOrNull() }) {
                    queued.decrementAndGet()
                    pending.ack?.completeExceptionally(why)
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
    public suspend fun append(
        records: List<ByteArray>,
        policy: AckPolicy = AckPolicy.WRITTEN,
    ): Offset? {
        require(records.isNotEmpty()) { "an empty batch has no base offset to report" }
        // Checked before the send rather than after it: sending into a closed channel throws
        // `ClosedSendChannelException`, which says the channel is shut but not why — and the caller
        // has to tell "the broker is stopping" from "this partition is broken" to answer at all.
        failure?.let { existing ->
            if (!tryResume()) throw WriterFailedException(existing)
        }

        val ack = if (policy == AckPolicy.NONE) null else CompletableDeferred<Offset>()
        val outbox = mailbox
        queued.incrementAndGet()
        try {
            outbox.send(WriteCommand(records, policy, ack))
        } catch (closed: ClosedSendChannelException) {
            // The writer died between the check above and this send, or a resume swapped the
            // mailbox under it. Either way this batch never reached the loop, so the loop's drain
            // will not count it down — this is the one place that has to.
            queued.decrementAndGet()
            throw failure?.let { WriterFailedException(it) } ?: WriterClosedException().initCause(closed)
        }
        return ack?.await()
    }

    /**
     * Brings a failed writer back, if it is worth trying yet.
     *
     * The fault this exists for is a full volume: `MAPPED` is the default, and a write into a
     * mapping whose backing store cannot grow is a SIGBUS the JVM raises as `InternalError`. What
     * makes coming back safe is where that fault leaves the segment. `MappedSegmentWriter.append`
     * advances its `written` **after** all three stores, and stores the length prefix **last**, so
     * a fault leaves the write position exactly where it was and the end-of-log marker exactly
     * where it was. Nothing was published: the high watermark never moved, and no reader ever saw
     * the attempt. Resuming is therefore starting a new loop over the same log, not repairing one.
     *
     * It does not reopen the log, and that is deliberate. Readers hold the same segments and go on
     * reading right through the failure — the one thing that still works — and closing the mapping
     * under them to rebuild it would take that away to buy back writes.
     *
     * Attempts are rate-limited to one per [resumeCooldownMillis]. Retrying per request would mean
     * a SIGBUS per request while the volume is still full; the cooldown makes the cost a constant
     * rather than a multiple of the load, and the producer that arrives after somebody freed space
     * still gets its record written rather than a refusal that outlives its cause.
     *
     * @return true when the writer is running again — including when another caller resumed it.
     */
    private suspend fun tryResume(): Boolean {
        if (closed) return false
        val since = System.nanoTime() - lastResumeNanos
        if (lastResumeNanos != 0L && since < resumeCooldownMillis * NANOS_PER_MILLI) return failure == null
        if (!resuming.compareAndSet(false, true)) return failure == null
        try {
            if (failure == null) return true
            lastResumeNanos = System.nanoTime()
            // The old loop set `failure` on its way out, so it is at or past its `finally`. Joined
            // rather than assumed: swapping the mailbox under a loop that still holds it is how a
            // batch would land in a channel nobody reads.
            job.join()
            if (closed) return false
            mailbox = Channel(mailboxCapacity)
            failure = null
            job = launchLoop()
            return true
        } finally {
            resuming.set(false)
        }
    }

    /** Convenience for the common single-record case. Still goes through the batch path. */
    public suspend fun append(
        record: ByteArray,
        policy: AckPolicy = AckPolicy.WRITTEN,
    ): Offset? = append(listOf(record), policy)

    /** Stops accepting new batches and waits for everything already queued to be written. */
    public suspend fun close() {
        closed = true
        mailbox.close()
        job.join()
    }

    private suspend fun runLoop() {
        val group = ArrayList<WriteCommand>()
        while (true) {
            val first = awaitCommand() ?: break
            group.add(first)
            // Published before the first write, cleared after the acks: between those two points a
            // failure would otherwise leave this batch owned by nobody.
            inFlight = group
            // Drain without suspending. Everything already in the mailbox joins this group and
            // shares one barrier; anything that arrives later waits for the next round. With no
            // window the group size still self-adjusts to the load, because a slow barrier lets
            // more messages accumulate behind it.
            while (true) {
                val more = mailbox.tryReceive().getOrNull() ?: break
                group.add(more)
            }
            if (groupWindowMillis > 0) collectDuring(group)

            var needsForce = false
            for (command in group) {
                command.baseOffset = writeBatch(command.records)
                recordsSinceFlush += command.records.size
                if (command.policy == AckPolicy.FORCED) needsForce = true
            }
            if (needsForce || countTriggerReached()) flushNow()

            // Acknowledged only after the barrier, and only after every write in the group — a
            // producer that hears "written" must not be able to observe a log that disagrees.
            // The count goes down first for the same reason it does on the failure path: the
            // acknowledgement releases the producer, so anything it may then read has to be true
            // already.
            queued.addAndGet(-group.size)
            inFlight = emptyList()
            for (command in group) {
                command.ack?.complete(command.baseOffset!!)
            }
            group.clear()
            // After the acks, not before: a reader woken by this must find the records already
            // readable, and `Log.nextOffset` is what makes them so.
            watermark.value = log.nextOffset
        }
    }

    /**
     * Holds the group open for [groupWindowMillis], taking whatever else arrives.
     *
     * The window is measured from the moment the **first** command of the group arrived, not
     * extended on every arrival: a steady stream would otherwise hold the barrier off for ever,
     * which is the same trap the client's accumulator has on its linger.
     *
     * `select`, not `withTimeoutOrNull(receive)`, for the reason written at [awaitCommand]: a
     * cancelled receive can swallow a command, and the producer waiting on it never hears back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectDuring(group: MutableList<WriteCommand>) {
        val deadline = System.nanoTime() + groupWindowMillis * 1_000_000
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) return
            val received =
                select<ChannelResult<WriteCommand>?> {
                    mailbox.onReceiveCatching { it }
                    onTimeout(remaining) { null }
                } ?: return
            group.add(received.getOrNull() ?: return)
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

    public companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Deep enough that a burst does not immediately block producers, shallow enough that the
         * backlog stays bounded. Once it is full, `send` suspends — which is the correct
         * backpressure: the alternative is an unbounded mailbox, where overload turns into an
         * OutOfMemoryError instead of a slowdown. At a 64 MiB heap that is not a hypothetical.
         */
        public const val DEFAULT_MAILBOX_CAPACITY: Int = 1024
    }
}

/** Thrown to producers still waiting when the writer's scope is cancelled. */
public class WriterClosedException : IllegalStateException("partition writer was closed before the batch was written")

/**
 * The partition's writer died, and this partition cannot be written to again.
 *
 * Not the same as [WriterClosedException], which is an orderly shutdown. This one carries the
 * failure that killed the loop — a full volume being the case it was written for, where an `mmap`ed
 * write faults as `InternalError` rather than as anything an IO path would think to catch.
 *
 * It exists so that the failure has a name on the wire. Before it, a producer that arrived after
 * the writer died had its connection dropped mid-response, which is indistinguishable from a
 * network fault; the batch already in flight was never answered at all (issue #15).
 */
public class WriterFailedException(
    cause: Throwable,
) : IllegalStateException("partition writer died and cannot accept writes: $cause", cause)
