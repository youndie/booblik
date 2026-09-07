package io.github.youndie.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.youndie.booblik.Offset
import io.github.youndie.booblik.PartitionId
import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.log.PartitionWriter
import io.github.youndie.booblik.net.client.BooblikClient
import io.github.youndie.booblik.net.wire.ErrorCode
import io.github.youndie.booblik.storage.Log
import io.github.youndie.booblik.storage.PartitionLog
import io.github.youndie.booblik.storage.SegmentMode
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a producer hears when the partition's writer has died (issue #15).
 *
 * The failure this stands for is a full volume. `segment: mode=MAPPED` is the default, and a write
 * into a mapping whose backing store cannot grow raises `InternalError` rather than an `IOException`
 * — so the writer coroutine dies and, under the broker's `SupervisorJob`, takes nothing with it.
 *
 * Reproduced on the published 0.3.0 image before this was written: the process stayed up, its health
 * check reported healthy with a failing streak of zero, its own metrics said `backlog 1 errors 0`,
 * and the producer's request was never answered. A producer that connected afterwards had its
 * connection dropped mid-response, which a client cannot tell from the network going away.
 *
 * The writer here is given a log that faults on the first write, which is the same shape without
 * needing a full disk. The partition's *read* side is a real log, because that is also true of the
 * real failure — and is why the broker does not exit.
 *
 * **Every read from the socket is bounded**, and that is not belt-and-braces: the defect being
 * tested is a response that never comes, so an unbounded `receiveProduce` turns a regression into a
 * hung suite rather than a failing one. Found by mutating the fix out and watching the run stop
 * instead of go red.
 */
class PartitionUnavailableTest {
    private class FaultingLog : Log {
        override val nextOffset: Offset = Offset.ZERO

        override fun hasRoomFor(payloadSize: Int): Boolean = true

        override fun append(
            payload: ByteArray,
            from: Int,
            length: Int,
        ): Offset = throw InternalError("a fault occurred in an unsafe memory access operation")

        override fun force() = Unit
    }

    /**
     * Runs [read] with a deadline, so "no answer ever" fails the test instead of stopping it.
     *
     * A thread rather than a coroutine because [BooblikClient] is blocking: the point is to stop
     * waiting on the socket, and only interrupting the waiter does that.
     */
    private fun <T> bounded(read: () -> T): T {
        // A **daemon** thread, and that is the second half of not hanging. Interrupting a thread
        // blocked in a plain socket read does not unblock it, so a non-daemon reader that never
        // gets its answer keeps the test JVM alive after the test itself has failed — the suite
        // then stops instead of going red, which is exactly the failure this helper was added to
        // prevent. Found by mutating the fix out a second time.
        val pool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        try {
            val answer = pool.submit(read)
            return try {
                answer.get(10, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                answer.cancel(true)
                throw AssertionError("the broker never answered — the request was abandoned (issue #15)", timeout)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withBrokenWriter(body: (BooblikClient) -> Unit) = withBrokenWriter { client, _ -> body(client) }

    @OptIn(ExperimentalPathApi::class)
    private fun withBrokenWriter(body: (BooblikClient, PartitionHandle) -> Unit) {
        val dir = Files.createTempDirectory("booblik-unavailable")
        val scope = CoroutineScope(SupervisorJob())
        val log = PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, 1 shl 20)
        val handle = PartitionHandle(log, PartitionWriter(FaultingLog(), scope))
        val registry = PartitionRegistry.of(PartitionRegistry.Key(TOPIC, PARTITION) to handle)
        val server = BooblikServer(registry, ServerConfig(port = 0, bindAddress = "127.0.0.1"))
        try {
            val address = server.start()
            BooblikClient(address).use { client -> body(client, handle) }
        } finally {
            server.close()
            scope.cancel()
            log.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the batch in flight when the writer dies is refused by code`() {
        withBrokenWriter { client ->
            client.sendProduce(TOPIC, PARTITION, listOf("first".toByteArray()))
            val answer = bounded { client.receiveProduce() }

            // Before M-160 there was no answer at all: the batch had been taken out of the mailbox,
            // so draining the mailbox on the way out did not reach it.
            assertEquals(ErrorCode.PARTITION_UNAVAILABLE, answer.error)
        }
    }

    @Test
    fun `a producer arriving afterwards is refused rather than disconnected`() {
        withBrokenWriter { client ->
            client.sendProduce(TOPIC, PARTITION, listOf("first".toByteArray()))
            bounded { client.receiveProduce() }

            // The second one is the case that used to close the connection mid-response — which a
            // client reports as "broker closed the connection", indistinguishable from a network
            // fault, and impossible to act on.
            client.sendProduce(TOPIC, PARTITION, listOf("second".toByteArray()))
            assertEquals(ErrorCode.PARTITION_UNAVAILABLE, bounded { client.receiveProduce() }.error)
        }
    }

    @Test
    fun `the connection stays usable and metadata still answers`() {
        withBrokenWriter { client ->
            client.sendProduce(TOPIC, PARTITION, listOf("first".toByteArray()))
            bounded { client.receiveProduce() }

            // Same connection, and the reason a refusal is right rather than a disconnect: framing
            // was intact and the broker understood the request. It is also why the health check
            // cannot see this — METADATA answers from state the dead writer never touched.
            client.sendMetadata(listOf(TOPIC))
            val metadata = bounded { client.receiveMetadata() }

            assertEquals(ErrorCode.NONE, metadata.error)
            assertTrue(
                metadata.topics
                    .single()
                    .partitions
                    .isNotEmpty(),
            )
        }
    }

    private companion object {
        val TOPIC = TopicName("orders")
        val PARTITION = PartitionId(0)
    }

    @Test
    fun `a connection dropped before its session starts is not invisible`() {
        // M-164's shape: a consumer reading `broker closed the connection` several times a second
        // while the broker's own line said `conns 0 errors 0`. `conns` counts from `serve`, so a
        // connection that fails in `configure` or `register` moves `acceptFailures` and nothing
        // else — and that counter was collected since M-64 and printed by nobody. The incident
        // itself has no reproduction (the reporter kept no offset), so this pins the number being
        // on screen, which is what the next occurrence needs.
        val quiet = snapshotWith(acceptFailures = 0)
        assertTrue(
            "accept-failed" !in quiet.since(quiet, 1_000),
            "a broker that refused nothing should not be talking about accept failures",
        )

        val noisy = snapshotWith(acceptFailures = 7)
        assertTrue(
            "accept-failed 7" in noisy.since(noisy, 1_000),
            "the line hides the only counter that would name this: ${noisy.since(noisy, 1_000)}",
        )
    }

    private fun snapshotWith(acceptFailures: Long) =
        Metrics.Snapshot(
            produceRequests = 0,
            fetchRequests = 0,
            fetchBytes = 0,
            errors = 0,
            sessionFailures = 0,
            connectionsAccepted = acceptFailures,
            acceptFailures = acceptFailures,
            heldFetches = 0,
            connectionsOpened = 0,
            openConnections = 0,
            partitions = emptyList(),
        )

    @Test
    fun `the metrics line names a partition that is refusing`() {
        // The number that was missing. On the published 0.3.0 image the line read
        // `backlog 1 errors 0` while the broker accepted nothing, and nothing in it said which
        // partition was refusing — or that any was. Health stays green on purpose (a restart does
        // not fix a full volume, and failing the check would take the working read side out of
        // service too), so this line is where an operator finds out.
        withBrokenWriter { client, handle ->
            val metrics = Metrics()
            val before = metrics.snapshot(null)
            assertTrue(
                "unavailable" !in before.since(before, 1_000),
                "a healthy broker should not be talking about unavailable partitions",
            )

            bounded { client.sendProduce(TOPIC, PARTITION, listOf("x".toByteArray())) }
            bounded { client.receiveProduce() }

            assertTrue(handle.writer.failure != null, "the fixture's writer did not fail")
            val snapshot =
                Metrics.Snapshot(
                    produceRequests = 1,
                    fetchRequests = 0,
                    fetchBytes = 0,
                    errors = 1,
                    sessionFailures = 0,
                    connectionsAccepted = 1,
                    acceptFailures = 0,
                    heldFetches = 0,
                    connectionsOpened = 1,
                    openConnections = 1,
                    partitions =
                        listOf(
                            Metrics.PartitionSnapshot(
                                topic = TOPIC.value,
                                partition = PARTITION.value,
                                logStartOffset = 0,
                                logEndOffset = 0,
                                segments = 1,
                                sizeInBytes = 0,
                                recordsWritten = handle.writer.recordsWritten,
                                bytesWritten = handle.writer.bytesWritten,
                                flushes = handle.writer.flushes,
                                mailboxDepth = handle.writer.mailboxDepth,
                                unavailable = handle.writer.failure != null,
                            ),
                        ),
                )
            assertTrue(
                "unavailable 1/1" in snapshot.since(snapshot, 1_000),
                "the line does not say the partition is refusing: ${snapshot.since(snapshot, 1_000)}",
            )
        }
    }
}
