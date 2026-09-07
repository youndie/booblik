package ru.workinprogress.booblik.dev.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arbiter of the queue, on its own.
 *
 * Everything the sample claims about task distribution comes down to this fold: who wins a task is
 * decided by replaying the claims partition in order, and by nothing else. Until now that was
 * asserted only end to end, which needs Docker, a broker and three workers — and which cannot
 * distinguish "the rule is right" from "the timing happened to work out".
 *
 * The property that matters most is the one the design was chosen for: **the verdict is a pure
 * function of the log**. A lease expires by the timestamp written into the claim being examined,
 * never by the reader's clock, so two workers replaying the same records agree even when their
 * clocks do not.
 */
class ClaimStateTest {
    private companion object {
        const val LEASE = 30_000L
        const val TASK = 7L

        fun claim(
            worker: String,
            at: Long,
            task: Long = TASK,
            lease: Long = LEASE,
        ) = ClaimRecord(ClaimRecord.CLAIM, worker, task, at, lease)

        fun done(
            worker: String,
            task: Long = TASK,
        ) = ClaimRecord(ClaimRecord.DONE, worker, task)

        /** Replays records in order, exactly as every worker does. */
        fun fold(records: List<ClaimRecord>): ClaimState =
            records.foldIndexed(ClaimState()) { index, state, record -> state.apply(record, index + 1L) }
    }

    @Test
    fun `the first claim on a free task wins and the second does not`() {
        val state = fold(listOf(claim("a", at = 1000), claim("b", at = 1001)))

        assertTrue(state.holds("a", TASK, 1000), "the first claim in the log should hold the task")
        assertFalse(state.holds("b", TASK, 1001), "the second claim landed inside a live lease")
    }

    @Test
    fun `a claim after the lease has lapsed takes the task over`() {
        // Judged by the second claim's own timestamp: it was written after the first lease ran out,
        // so it wins — whatever the clock of whoever is reading this log says.
        val state = fold(listOf(claim("a", at = 1000), claim("b", at = 1000 + LEASE + 1)))

        assertTrue(state.holds("b", TASK, 1000 + LEASE + 1))
        assertFalse(state.holds("a", TASK, 1000))
    }

    @Test
    fun `a finished task is not claimable again`() {
        val state = fold(listOf(claim("a", at = 1000), done("a"), claim("b", at = 1000 + LEASE + 1)))

        assertTrue(TASK in state.done)
        assertNull(state.leases[TASK], "a finished task holds no lease")
        assertFalse(state.holds("b", TASK, 1000 + LEASE + 1), "a claim on a finished task is a no-op")
    }

    @Test
    fun `the verdict does not depend on the reader's clock`() {
        // The same records folded twice give the same answer, because `apply` never reads a clock.
        // What a local clock may change is only which tasks a worker will *try*, which is
        // `claimable` and is asked separately below.
        val log = listOf(claim("a", at = 1000), claim("b", at = 1200), claim("c", at = 40_000))

        assertEquals(fold(log).leases, fold(log).leases)
        assertEquals(fold(log).done, fold(log).done)
        assertTrue(fold(log).holds("c", TASK, 40_000), "the third claim is the one past the lease")
    }

    @Test
    fun `claimable is where the local clock is allowed to matter`() {
        val state = fold(listOf(claim("a", at = 1000)))

        assertEquals(emptyList(), state.claimable(listOf(TASK), now = 1001), "the lease is live")
        assertEquals(listOf(TASK), state.claimable(listOf(TASK), now = 1000 + LEASE + 1), "the lease has lapsed")
    }

    @Test
    fun `tasks do not interfere with each other`() {
        val state = fold(listOf(claim("a", at = 1000, task = 1), claim("b", at = 1000, task = 2)))

        assertTrue(state.holds("a", 1, 1000))
        assertTrue(state.holds("b", 2, 1000))
    }

    @Test
    fun `a record that is neither a claim nor a completion only moves the position`() {
        // Forward compatibility, and cheaply: a worker running old code must not stall or
        // misinterpret a record type it has never heard of.
        val state = fold(listOf(claim("a", at = 1000), ClaimRecord("renew", "a", TASK, 1500, LEASE)))

        assertEquals(2, state.consumedUpTo)
        assertTrue(state.holds("a", TASK, 1000), "an unknown record must not disturb the lease")
    }
}
