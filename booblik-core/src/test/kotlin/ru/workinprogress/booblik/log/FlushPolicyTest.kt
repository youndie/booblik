package ru.workinprogress.booblik.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M-50: the flush policy, and the batch it must never eat. */
class FlushPolicyTest {
    private fun <T> withWriter(
        policy: FlushPolicy,
        body: suspend (PartitionWriter, CountingLog) -> T,
    ): T {
        val log = CountingLog()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking {
                val writer = PartitionWriter(log, scope, flushPolicy = policy)
                val result = body(writer, log)
                writer.close()
                result
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a time trigger must not swallow the batch that arrives with it`() {
        // The regression this file exists for. Waiting for the next batch with a timeout is the
        // obvious way to implement a time trigger and it is wrong: cancelling a `receive` can
        // consume an element and drop it, so a producer waits forever for an acknowledgement that
        // will never come. With a one-millisecond window the race happens within a few hundred
        // appends; the broker showed it as `backlog 1` and a client timeout.
        //
        // `select` is the fix — it either takes the element or takes the timeout, never both.
        withWriter(FlushPolicy(everyMillis = 1)) { writer, log ->
            // Concurrent producers widen the window: the more often a batch can land exactly as the
            // timer fires, the more likely a lost element is to surface here rather than in
            // production. This is a net, not a proof — the fix is justified by the cancellation
            // contract of `receive`, and this is where a regression has somewhere to be caught.
            withTimeout(60_000) {
                coroutineScope {
                    repeat(16) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(100) { writer.append("p$producer-$it".toByteArray()) }
                        }
                    }
                }
            }
            assertEquals(1600, log.appendCount, "every batch must be written")
        }
    }

    @Test
    fun `a count trigger forces once per N records and not per batch`() {
        withWriter(FlushPolicy(everyRecords = 10)) { writer, log ->
            repeat(10) { writer.append(List(3) { i -> "r$i".toByteArray() }) }
            // Thirty records at one barrier per ten. Not three: the trigger is checked once per
            // group, and a group of three records overshoots to twelve before it fires — so two.
            // What matters is that it is far below ten, which is what one barrier per batch
            // would cost.
            assertEquals(30, log.appendCount)
            assertTrue(log.forceCount in 2..4, "expected a barrier per ~10 records, got ${log.forceCount}")
        }
    }

    @Test
    fun `no policy means no barriers of its own`() {
        withWriter(FlushPolicy.Disabled) { writer, log ->
            repeat(20) { writer.append("r-$it".toByteArray(), AckPolicy.WRITTEN) }
            assertEquals(0, log.forceCount, "WRITTEN never buys a barrier by itself")
        }
    }

    @Test
    fun `an idle writer still flushes what it has`() {
        // The records right before the traffic stopped are exactly the ones a time policy is for,
        // and they are the ones a naive implementation never reaches.
        withWriter(FlushPolicy(everyMillis = 50)) { writer, log ->
            writer.append("last one before it went quiet".toByteArray())
            kotlinx.coroutines.delay(400)
            assertTrue(log.forceCount >= 1, "an idle broker must still flush, got ${log.forceCount}")
        }
    }
}
