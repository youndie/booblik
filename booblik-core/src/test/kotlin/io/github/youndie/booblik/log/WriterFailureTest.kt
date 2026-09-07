package io.github.youndie.booblik.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.storage.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a partition writer does when a write cannot land at all (issue #15).
 *
 * The case it was written for is a full volume, where the failure does not arrive as an
 * `IOException`: `segment: mode=MAPPED` is the default, and a write into a mapping whose backing
 * store cannot grow is a SIGBUS, which the JVM raises as `InternalError`. Nothing in an IO path
 * catches that, so the loop dies — and the question this test settles is what the producers hear.
 *
 * Before M-160 the answer was nothing at all. The batch being written had already been taken out
 * of the mailbox, so draining the mailbox on the way out did not reach it, and its `ack` was never
 * completed. Reproduced on the published 0.3.0 image as a broker with `backlog 1`, `errors 0` and a
 * health check reporting healthy while it accepted nothing.
 */
class WriterFailureTest {
    /** A log that writes normally until it is told to fault, exactly as a full volume would. */
    private class FaultingLog(
        /** Writes before the fault. Raised by a test to say the volume has room again. */
        var failAfter: Int,
    ) : Log {
        var appended = 0
            private set

        override var nextOffset: Offset = Offset.ZERO
            private set

        override fun hasRoomFor(payloadSize: Int): Boolean = true

        override fun append(
            payload: ByteArray,
            from: Int,
            length: Int,
        ): Offset {
            if (appended >= failAfter) {
                // The exact class the JVM raises for a SIGBUS on a mapped write. An IOException
                // would make this test easier and the case it stands for imaginary.
                throw InternalError("a fault occurred in an unsafe memory access operation")
            }
            appended += 1
            val assigned = nextOffset
            nextOffset = assigned.inc()
            return assigned
        }

        override fun force() = Unit
    }

    private fun <T> withWriter(
        failAfter: Int,
        resumeCooldownMillis: Long = 5_000,
        body: suspend CoroutineScope.(PartitionWriter) -> T,
    ): T = withLog(FaultingLog(failAfter), resumeCooldownMillis) { _, writer -> body(writer) }

    private fun <T> withLog(
        log: FaultingLog,
        resumeCooldownMillis: Long = 5_000,
        body: suspend CoroutineScope.(FaultingLog, PartitionWriter) -> T,
    ): T {
        // SupervisorJob because that is what the broker uses, and it is half of why this failure was
        // silent: a writer that dies under a supervisor takes nothing with it and tells nobody.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = PartitionWriter(log, scope, resumeCooldownMillis = resumeCooldownMillis)
        try {
            return runBlocking { body(log, writer) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the batch being written when the volume fills is answered, not abandoned`() {
        withWriter(failAfter = 0) { writer ->
            val failure =
                assertNotNull(
                    withTimeoutOrNull(5_000) {
                        assertFailsWith<WriterFailedException> { writer.append("first".toByteArray()) }
                    },
                    "the producer of the in-flight batch was never answered — this is issue #15",
                )
            assertTrue(
                failure.message!!.contains("unsafe memory access"),
                "the refusal should carry what actually killed the writer: ${failure.message}",
            )
        }
    }

    @Test
    fun `a producer arriving after the writer died is refused rather than left waiting`() {
        withWriter(failAfter = 0) { writer ->
            withTimeoutOrNull(5_000) { runCatching { writer.append("first".toByteArray()) } }
            // The mailbox is closed by now. Before M-160 this blocked or threw
            // ClosedSendChannelException, which says the channel is shut but not why.
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("second".toByteArray()) }
                },
                "a producer arriving after the failure hung instead of being refused",
            )
        }
    }

    @Test
    fun `the backlog does not keep counting a batch nobody will ever write`() {
        withWriter(failAfter = 0) { writer ->
            // `Unconfined`, and that is the whole test rather than a detail. It makes the producer's
            // continuation resume **in the writer's own thread at the instant the ack completes**,
            // so the depth below is read at the completion point: whatever the failure path has not
            // done by that line is not done. Read from the test's thread instead, this asks a
            // question about scheduling — it passed on a laptop for fifteen runs and failed on a
            // two-core runner, which is not a test, it is a coin.
            val depthWhenRefused =
                async(Dispatchers.Unconfined) {
                    assertFailsWith<WriterFailedException> { writer.append("first".toByteArray()) }
                    writer.mailboxDepth
                }

            // `backlog 1` for ever is what an operator saw, and it is the number that made the
            // failure undiagnosable from outside: a queue that never drains and no errors.
            assertEquals(
                0,
                assertNotNull(
                    withTimeoutOrNull(5_000) { depthWhenRefused.await() },
                    "the producer of the in-flight batch was never answered",
                ),
                "a producer that has been refused can read the backlog on its next instruction, " +
                    "and its own batch was still counted there",
            )
        }
    }

    @Test
    fun `what was written before the failure is still there`() {
        withWriter(failAfter = 2) { writer ->
            assertEquals(Offset(0), writer.append("one".toByteArray()))
            assertEquals(Offset(1), writer.append("two".toByteArray()))
            // Bounded like every other wait here. An unbounded one turns a regression into a suite
            // that stops rather than one that goes red — which is what it did the first time the
            // fix was mutated out, and cost a thread dump to find.
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("three".toByteArray()) }
                },
                "the batch after the failure was never answered",
            )
            // The reason the process is not made to exit: the log up to the failure is intact and
            // readable, and a broker that killed itself would take that away too.
            assertEquals(Offset(2), writer.highWatermark.value)
        }
    }

    @Test
    fun `an orderly close is still an orderly close`() {
        // The failure path must not swallow the ordinary one: a writer closed on the way down
        // reports being closed, not being broken.
        withWriter(failAfter = 100) { writer ->
            val queued = async(start = CoroutineStart.LAZY) { writer.append("x".toByteArray()) }
            writer.close()
            queued.cancel()
            assertEquals(null, writer.failure, "a clean shutdown is not a failure")
        }
    }

    @Test
    fun `a writer that faulted writes again once the volume has room`() {
        // The field case in one method: the volume fills, the partition refuses, somebody frees
        // space, and the next producer along is answered instead of being told to wait for a
        // restart. Before this, only restarting the broker brought the partition back — shown on
        // the stand after the soak, with 72% of the volume free and the partition still refusing.
        withLog(FaultingLog(failAfter = 1), resumeCooldownMillis = 0) { log, writer ->
            assertEquals(Offset(0), writer.append("before".toByteArray()))
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("during".toByteArray()) }
                },
                "the batch that met the full volume was never answered",
            )

            log.failAfter = Int.MAX_VALUE

            val after =
                assertNotNull(
                    withTimeoutOrNull(5_000) { writer.append("after".toByteArray()) },
                    "the writer never came back after the fault cleared",
                )
            // Offset 1, not 2: the record that faulted was never published, so it never took a
            // number. `MappedSegmentWriter` advances its position after all three stores, which is
            // why coming back is starting a loop and not repairing a log.
            assertEquals(Offset(1), after)
            // Waited for, not read. The watermark is published **after** the acknowledgements on
            // purpose — a reader woken by it must find the records already readable — so a producer
            // holding its offset may legitimately see a watermark that has not moved yet. Reading it
            // at that instant asks a question about scheduling: it passed here and failed on a
            // two-core runner, in the same file where that mistake was corrected this morning.
            assertNotNull(
                withTimeoutOrNull(5_000) { writer.highWatermark.first { it == Offset(2) } },
                "the record written after the resume never became readable",
            )
            assertEquals(0, writer.mailboxDepth)
            assertEquals(null, writer.failure)
        }
    }

    @Test
    fun `within the cooldown the refusal is immediate, however many producers ask`() {
        // A retry per request would mean a fault per request while the volume is still full. The
        // cooldown makes a broken partition cost a constant instead of a multiple of the load.
        withLog(FaultingLog(failAfter = 0), resumeCooldownMillis = 60_000) { log, writer ->
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("first".toByteArray()) }
                },
                "the in-flight batch was never answered",
            )

            // The first producer after a failure gets its attempt straight away — a fault that
            // clears by itself should not cost a minute of refusals — and that attempt faults
            // again, because the volume is still full. The cooldown starts there.
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("second".toByteArray()) }
                },
                "the one immediate retry was never answered",
            )

            // Now the volume has room, and the cooldown still says no. This is the assertion: a
            // rate limit that only holds while the fault persists would not be a rate limit.
            log.failAfter = Int.MAX_VALUE
            repeat(3) {
                assertNotNull(
                    withTimeoutOrNull(5_000) {
                        assertFailsWith<WriterFailedException> { writer.append("early".toByteArray()) }
                    },
                    "a producer inside the cooldown was left waiting instead of refused",
                )
            }
            assertEquals(0, writer.mailboxDepth, "refused batches are not left counted as queued")
        }
    }

    @Test
    fun `a closed writer is not brought back by a late producer`() {
        // Resume must not fight shutdown. The order here is the one that matters and the one the
        // first version of this test missed: the writer has to have **failed** and then been
        // closed, because a writer that only closed never reaches the resume path at all — so a
        // test that closes a healthy writer passes whether the guard is there or not.
        withLog(FaultingLog(failAfter = 0), resumeCooldownMillis = 0) { log, writer ->
            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("first".toByteArray()) }
                },
                "the in-flight batch was never answered",
            )

            writer.close()
            // Everything a resume would need is now in place except permission: the log is healthy
            // and the cooldown is zero. A writer on the way down must still refuse.
            log.failAfter = Int.MAX_VALUE

            assertNotNull(
                withTimeoutOrNull(5_000) {
                    assertFailsWith<WriterFailedException> { writer.append("late".toByteArray()) }
                },
                "a producer after close was left waiting, or was served by a resurrected writer",
            )
        }
    }
}
