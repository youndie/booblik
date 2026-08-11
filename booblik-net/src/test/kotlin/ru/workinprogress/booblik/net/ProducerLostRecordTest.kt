package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.ProducerConfig
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The accumulator must not lose a record, and what loses one is the flush timer firing as a record
 * arrives.
 *
 * This is the client-side twin of `FlushPolicyTest`, and it exists because the same mistake was
 * made twice in the same repository. `Producer` waited for the next record with
 * `withTimeoutOrNull(mailbox.receive())`, and cancelling a `receive` can take an element off the
 * channel and then drop it. The caller's `CompletableDeferred` is then never completed: that one
 * send hangs for ever while the producer goes on serving everybody else, which is about the least
 * legible failure available.
 *
 * Found by the sample rather than by a test. A publisher writing events every 500 ms and tasks
 * every 10 ms through one producer stopped after a single task and kept publishing events happily.
 * Nothing in this suite had ever driven **two topics at different rates** through one accumulator,
 * and that is what it takes to land inside the window.
 */
class ProducerLostRecordTest {
    private companion object {
        const val LINGER = 2L

        // Enough rounds that the collision is reached rather than hoped for: against the broken
        // accumulator this hangs within the first few hundred.
        const val ROUNDS = 1500
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `two topics at different rates through one producer lose nothing`() =
        runBlocking {
            val dir = Files.createTempDirectory("booblik-producer-loss")
            val scope = CoroutineScope(SupervisorJob())
            val fast = TopicName("fast")
            val slow = TopicName("slow")
            val entries =
                listOf(fast, slow).map { topic ->
                    val log = PartitionLog.open(dir.resolve(topic.value), SegmentMode.FILE_CHANNEL)
                    PartitionRegistry.Key(topic, PartitionId(0)) to PartitionHandle(log, PartitionWriter(log, scope))
                }
            val server =
                BooblikServer(
                    PartitionRegistry.of(*entries.toTypedArray()),
                    ServerConfig(port = 0, bindAddress = "127.0.0.1"),
                )
            try {
                val address = server.start()
                BooblikConnection(address, scope).use { connection ->
                    // The record interval is deliberately the linger window. The accumulator arms
                    // its timer when the first record of a batch arrives, so a record sent one
                    // window later lands on the expiry — which is the only moment a cancelled
                    // receive has anything to swallow. Sending in a tight loop instead fills the
                    // batch and never reaches the timeout at all, which is why an earlier version
                    // of this test passed against the broken code.
                    Producer(connection, scope, ProducerConfig(lingerMillis = LINGER)).use { producer ->
                        val all =
                            mutableListOf<kotlinx.coroutines.CompletableDeferred<ru.workinprogress.booblik.Offset>>()
                        repeat(ROUNDS) { round ->
                            val topic = if (round % 2 == 0) fast else slow
                            all += producer.send(topic, PartitionId(0), "record-$round".toByteArray())
                            delay(LINGER)
                        }
                        // The failure is a hang rather than a wrong answer, so the assertion has to
                        // be a deadline. Without the fix this times out on the send whose record the
                        // cancelled receive swallowed.
                        val offsets =
                            assertNotNull(
                                withTimeoutOrNull(30_000) { all.awaitAll() },
                                "a send was never answered — the accumulator dropped a record",
                            )
                        assertEquals(ROUNDS, offsets.size)
                    }
                }
            } finally {
                server.close()
                scope.cancel()
                entries.forEach { it.second.log.close() }
                dir.deleteRecursively()
            }
        }
}
