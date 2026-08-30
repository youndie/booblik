package ru.workinprogress.booblik.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.storage.Log
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
        private val failAfter: Int,
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
        body: suspend CoroutineScope.(PartitionWriter) -> T,
    ): T {
        // SupervisorJob because that is what the broker uses, and it is half of why this failure was
        // silent: a writer that dies under a supervisor takes nothing with it and tells nobody.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = PartitionWriter(FaultingLog(failAfter), scope)
        try {
            return runBlocking { body(writer) }
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
            withTimeoutOrNull(5_000) { runCatching { writer.append("first".toByteArray()) } }
            // `backlog 1` for ever is what an operator saw, and it is the number that made the
            // failure undiagnosable from outside: a queue that never drains and no errors.
            assertEquals(0, writer.mailboxDepth, "the abandoned batch is still counted as queued")
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
}
