package ru.workinprogress.booblik.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.wire.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-75: the scenarios from `docs/features/feature-subscribe-and-publish.md`, section 4б.
 *
 * These run against a real socket, so they wait in real time — `runTest`'s virtual clock does not
 * advance on network I/O, and pretending otherwise produces a test that looks instant and hangs for
 * seconds. Waits are therefore kept short and asserted as inequalities rather than as exact
 * durations: a test that demands a wakeup inside 50 ms on a loaded CI machine is a test that will
 * be deleted after it flakes twice.
 */
class LongFetchTest {
    /** «Догнавший потребитель ждёт, а не опрашивает» + «истёкшее ожидание — пустой ответ». */
    @Test
    fun `a caught-up fetch is held for its wait and then answered empty`() =
        withServer { server, client ->
            runBlocking {
                val end = produce(client, count = 2)

                val began = System.nanoTime()
                client.sendFetch(TOPIC, PARTITION, end, maxBytes = 1 shl 20, maxWaitMillis = 400)

                // Silence is asserted by how long the single read took, and **not** by racing a
                // `withTimeoutOrNull` against it. `receiveFetch` is a blocking socket read: a
                // cancelled timeout does not interrupt it, so the abandoned read stays on the
                // socket and eats the response the next read is waiting for. That version of this
                // test hung for ten minutes before the reason became obvious.
                val answer = withContext(Dispatchers.IO) { client.receiveFetch() }
                val elapsedMillis = (System.nanoTime() - began) / 1_000_000
                assertEquals(ErrorCode.NONE, answer.error)
                assertTrue(answer.records.isEmpty(), "an expired wait is an empty answer, not an error")
                assertEquals(end, answer.highWatermark)
                assertTrue(elapsedMillis >= 350, "answered after ${elapsedMillis}ms, before the wait expired")
                assertEquals(0, server.metrics.snapshot(null).heldFetches, "the gauge must come back down")
            }
        }

    /** «Запись во время ожидания будит немедленно, а не по таймауту». */
    @Test
    fun `a record arriving during the wait answers immediately`() =
        withServer { _, client ->
            runBlocking {
                val end = produce(client, count = 1)

                val began = System.nanoTime()
                client.sendFetch(TOPIC, PARTITION, end, maxBytes = 1 shl 20, maxWaitMillis = 10_000)

                // A second connection, because the held request occupies the first one — the very
                // property the design had to be explicit about.
                val answer =
                    async(Dispatchers.IO) {
                        BooblikClient(client.remoteAddress as java.net.InetSocketAddress).use { producer ->
                            delay(100)
                            producer.sendProduce(TOPIC, PARTITION, records(1))
                            producer.receiveProduce()
                        }
                        client.receiveFetch()
                    }.await()

                val elapsedMillis = (System.nanoTime() - began) / 1_000_000
                assertEquals(1, answer.records.size)
                assertTrue(
                    elapsedMillis < 5_000,
                    "woke after ${elapsedMillis}ms — that is the timeout, not the record",
                )
            }
        }

    /** «minBytes больше maxBytes отвергается кадром, соединение остаётся открытым». */
    @Test
    fun `minBytes larger than maxBytes is refused without closing the connection`() =
        withServer { _, client ->
            runBlocking {
                client.sendFetch(TOPIC, PARTITION, Offset.ZERO, maxBytes = 1024, minBytes = 2048)
                assertEquals(ErrorCode.CORRUPT_REQUEST, withContext(Dispatchers.IO) { client.receiveFetch() }.error)

                // Still usable: the frame was refused, not the session.
                val end = produce(client, count = 1)
                assertEquals(Offset(1), end)
            }
        }

    /** «Удержание не задерживает чужие запросы» — и его обратная сторона на одном соединении. */
    @Test
    fun `a held fetch blocks its own connection but not another`() =
        withServer { server, follower ->
            runBlocking {
                val end = produce(follower, count = 1)
                follower.sendFetch(TOPIC, PARTITION, end, maxBytes = 1 shl 20, maxWaitMillis = 1_500)
                waitForHeldFetch(server)

                val address = follower.remoteAddress as java.net.InetSocketAddress
                val produced = CompletableDeferred<Long>()
                withContext(Dispatchers.IO) {
                    BooblikClient(address).use { producer ->
                        val began = System.nanoTime()
                        producer.sendProduce(TOPIC, PARTITION, records(1))
                        producer.receiveProduce()
                        produced.complete((System.nanoTime() - began) / 1_000_000)
                    }
                }
                assertTrue(
                    produced.await() < 1_000,
                    "a produce on its own connection waited ${produced.await()}ms behind a held fetch",
                )
                withContext(Dispatchers.IO) { follower.receiveFetch() }
            }
        }

    private fun produce(
        client: BooblikClient,
        count: Int,
    ): Offset {
        client.sendProduce(TOPIC, PARTITION, records(count))
        return client.receiveProduce().logEndOffset
    }

    private fun records(count: Int) = List(count) { ByteArray(16) { b -> b.toByte() } }

    /** The gauge is the only way to know the broker has actually parked the request. */
    private suspend fun waitForHeldFetch(server: BooblikServer) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (server.metrics.snapshot(null).heldFetches == 0L) {
            check(System.nanoTime() < deadline) { "the broker never held the fetch" }
            delay(10)
        }
    }
}
