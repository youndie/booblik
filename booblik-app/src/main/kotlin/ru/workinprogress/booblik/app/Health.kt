package ru.workinprogress.booblik.app

import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.MetadataResult
import java.net.InetSocketAddress
import kotlin.system.exitProcess

/**
 * Asks the broker a question it can only answer if it is actually serving.
 *
 * ## Why not a TCP connect
 *
 * Because a TCP connect does not distinguish a working broker from a hung one. The kernel
 * completes the handshake into the listen backlog on its own; the process does not have to be
 * involved, or even alive in any useful sense. That is not a hypothetical — M-64 was a day spent
 * on a broker that accepted connections and answered none of them, and a connect-based check would
 * have reported it healthy throughout.
 *
 * METADATA is the cheapest request that requires the session loop to run, the registry to be
 * readable and a response to be encoded and written back. If that works, the broker is serving.
 *
 * ## What it costs
 *
 * A JVM per check — around 150 ms of startup (research §1.19) and a short-lived heap. That is
 * affordable at a 30-second interval and would not be at one second; the interval belongs in
 * whatever runs this, and the `Dockerfile` sets it explicitly rather than taking a default.
 *
 * Exit code 0 means serving. Anything else means not, and the reason goes to stderr — a health
 * check that fails silently turns an outage into a mystery.
 */
object Health {
    private const val DEFAULT_TIMEOUT_MILLIS = 5_000

    @JvmStatic
    fun main(args: Array<String>) {
        val host = args.getOrElse(0) { "127.0.0.1" }
        val port = args.getOrElse(1) { "9092" }.toIntOrNull()
        if (port == null || port !in 1..65535) {
            System.err.println("usage: health [host] [port]; port must be 1..65535, got ${args.getOrNull(1)}")
            exitProcess(2)
        }

        // The deadline lives here and not in `BooblikClient`, and that is deliberate twice over.
        // Bounding a request is the caller's policy — the client is a thin synchronous thing on
        // purpose — and more to the point, a health check without a deadline turns a hung broker
        // into a hung check, losing exactly what it was sent to find. A blocking read on a
        // `SocketChannel` does not answer to `SO_TIMEOUT`; closing the channel is what unblocks it,
        // so the watchdog closes the client and the read fails on its own.
        var answer: MetadataResult? = null
        var failure: Throwable? = null
        val client =
            runCatching { BooblikClient(InetSocketAddress(host, port)) }.getOrElse {
                System.err.println("booblik at $host:$port refused a connection: $it")
                exitProcess(1)
            }
        val worker =
            Thread {
                runCatching {
                    client.sendMetadata()
                    answer = client.receiveMetadata()
                }.onFailure { failure = it }
            }
        worker.isDaemon = true
        worker.start()
        worker.join(DEFAULT_TIMEOUT_MILLIS.toLong())
        if (worker.isAlive) {
            runCatching { client.close() }
            System.err.println(
                "booblik at $host:$port accepted the connection and did not answer in ${DEFAULT_TIMEOUT_MILLIS}ms",
            )
            exitProcess(1)
        }
        runCatching { client.close() }

        val result = answer
        if (result == null) {
            System.err.println("booblik at $host:$port did not answer METADATA: $failure")
            exitProcess(1)
        }
        if (result.error != ErrorCode.NONE) {
            System.err.println("booblik at $host:$port answered METADATA with ${result.error}")
            exitProcess(1)
        }
        println("booblik at $host:$port is serving ${result.topics.size} topic(s)")
    }
}
