package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.net.client.BooblikClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * M-64: the pipelined scenario, run enough times that a one-in-fifteen failure has to show up.
 *
 * `ServerTest.requests are answered in order` failed roughly once every fifteen runs with an EOF
 * where a response should have been, which is too rare to chase one `./gradlew test` at a time and
 * too common to ignore. This runs the same exchange [ROUNDS] times inside one JVM.
 *
 * Two things here are less obvious than the loop:
 *
 * * the client's exception is caught **inside** the fixture, because the interesting half of the
 *   evidence is on the server and letting the failure propagate tears the server down before
 *   anything can ask it what happened;
 * * accept-loop failures are collected across **all** rounds and reported at the end even when
 *   every round passed. The accept loop now survives an error rather than dying on it, which
 *   removes the symptom and leaves the cause — and a cause that shows up only as an absence of
 *   failures is one nobody will ever look at.
 */
class SessionStressTest {
    @Test
    fun `the pipelined exchange survives being repeated`() {
        val acceptFailures = mutableListOf<Throwable>()

        repeat(ROUNDS) { round ->
            var report: String? = null
            withServer { server, client ->
                try {
                    val ids = (0 until 3).map { client.sendProduce(TOPIC, PARTITION, records(2))!! }
                    val answers = (0 until 3).map { client.receiveProduce() }
                    assertEquals(ids, answers.map { it.correlationId })
                    assertEquals(listOf(0L, 2L, 4L), answers.map { it.baseOffset.value })
                } catch (e: Throwable) {
                    report = diagnose(round, e, server, client)
                }
                server.metrics.lastAcceptFailure?.let { acceptFailures += it }
            }
            report?.let { fail(it) }
        }

        if (acceptFailures.isNotEmpty()) {
            fail(
                "the accept loop failed ${acceptFailures.size} time(s) across $ROUNDS rounds and recovered; " +
                    "first: ${acceptFailures.first().stackTraceToString()}",
            )
        }
    }

    /** Everything worth knowing about a failed round, gathered while the server is still up. */
    private fun diagnose(
        round: Int,
        cause: Throwable,
        server: BooblikServer,
        client: BooblikClient,
    ): String {
        // The counters say whether the socket was ever taken off the accept queue. What separates
        // "the acceptor is dead" from "this one connection was lost" is whether a *second* client
        // gets served right now.
        val secondOpinion =
            runCatching {
                BooblikClient(server.address).use { fresh ->
                    fresh.sendProduce(TOPIC, PARTITION, records(1))
                    "a fresh connection was served: ${fresh.receiveProduce()}"
                }
            }.getOrElse { "a fresh connection also failed: $it" }
        val threads =
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.name.startsWith("booblik-") }
                .groupingBy { "${it.name}:${it.state}" }
                .eachCount()
        return "round $round of $ROUNDS\n" +
            "client was ${client.localAddress} -> ${client.remoteAddress}, " +
            "server listening on ${server.address}\n" +
            "client saw: ${cause.stackTraceToString()}\n" +
            "session saw: ${server.metrics.lastSessionFailure?.stackTraceToString() ?: "nothing"}\n" +
            "accept loop saw: ${server.metrics.lastAcceptFailure?.stackTraceToString() ?: "nothing"}\n" +
            "second opinion: $secondOpinion\n" +
            "threads: $threads\n" +
            "metrics: ${server.metrics.snapshot(null)}"
    }

    private fun records(count: Int) = List(count) { ByteArray(16) { b -> b.toByte() } }

    private companion object {
        // From the environment rather than a system property: `-D` on the Gradle command line
        // sets it on the Gradle JVM, not on the forked test JVM, so it would silently keep the
        // default and look like the knob does nothing.
        //
        // **Do not raise this past the ephemeral port range.** Every round opens a connection from
        // a fresh local port and leaves it in TIME_WAIT, so the range drains as the test runs;
        // macOS has 16384 of them (`sysctl net.inet.ip.portrange`). Runs at 40 000 rounds failed
        // at round 16 360 and 16 364 — twice, within four rounds of each other, which is the
        // signature of a ceiling rather than of a race. That is the harness running out of ports,
        // not the broker misbehaving, and it cost a round of chasing the wrong thing.
        val ROUNDS = System.getenv("BOOBLIK_STRESS_ROUNDS")?.toInt() ?: 300
    }
}
