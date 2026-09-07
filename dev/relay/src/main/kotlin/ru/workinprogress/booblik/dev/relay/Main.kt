package ru.workinprogress.booblik.dev.relay

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves records between Kafka and booblik, one direction per process.
 *
 * The point of the relay is where it sits: **outside** the broker. booblik does not speak the Kafka
 * protocol and is not going to — Kafka's record batch carries `baseOffset` and a CRC over the batch
 * inside the stored bytes, so speaking it means storing it, and booblik's whole read path is
 * `transferTo` of its own bytes straight from the segment. A relay keeps both formats intact and
 * pays the translation once, in user space, where it belongs
 * (docs/research/research-usecases.md, Р14).
 *
 * One module rather than two, with the direction in the environment: both directions share the
 * configuration, the HTTP surface, the batching and the reconnect, and differ in about forty lines
 * each — mostly in **where the position lives**, which is the interesting part and is worth having
 * side by side rather than in two repositories of scaffolding.
 *
 * What a relay cannot carry, and it matters both ways:
 *
 *  * **Kafka keys do not survive into booblik.** A Kafka record has a key on the wire; a booblik
 *    record does not — the key picks a partition inside the client and stops there. Going
 *    Kafka → booblik the key still does its job, choosing the booblik partition, so per-key
 *    ordering is preserved; but the key itself is not stored, so a round trip loses it. Enveloping
 *    it into the payload would keep it and would make every other booblik consumer parse an
 *    envelope it did not ask for, so this relay does not.
 *  * **Kafka headers and timestamps go the same way**, for the same reason.
 *  * **Both directions are at-least-once.** The position moves only after the write on the far side
 *    is acknowledged, so a crash in between repeats records rather than dropping them.
 */
fun main() {
    val config = RelayConfig.fromEnvironment()
    val stats = Stats()
    val scope = CoroutineScope(SupervisorJob())

    println("relay ${config.name}: ${config.direction}, ${config.kafkaTopic} <-> ${config.booblikTopic}")

    scope.launch {
        while (true) {
            try {
                when (config.direction) {
                    Direction.KAFKA_TO_BOOBLIK -> kafkaToBooblik(config, stats)
                    Direction.BOOBLIK_TO_KAFKA -> booblikToKafka(config, stats)
                }
            } catch (failure: Exception) {
                // A relay that dies when one side blinks is worse than no relay: the other side
                // keeps producing and the gap grows silently. Reconnecting is the whole job.
                println("relay ${config.name}: ${failure.message}, restarting the loop")
                stats.restarts.incrementAndGet()
                stats.lastFailure = failure.message
                delay(2000)
            }
        }
    }

    runBlocking {
        embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
                get("/health") { call.respondText("ok") }
                get("/stats") { call.respond(stats.snapshot(config)) }
            }
        }.start(wait = true)
    }
}

enum class Direction {
    KAFKA_TO_BOOBLIK,
    BOOBLIK_TO_KAFKA,
}

class Stats {
    val relayed = AtomicLong()
    val batches = AtomicLong()
    val restarts = AtomicLong()

    @Volatile
    var lastFailure: String? = null

    @Volatile
    var position: Long? = null

    @Volatile
    var lastRecord: String? = null

    fun observe(
        count: Int,
        at: Long?,
        sample: ByteArray?,
    ) {
        relayed.addAndGet(count.toLong())
        batches.incrementAndGet()
        position = at
        sample?.let { lastRecord = String(it).take(120) }
    }

    fun snapshot(config: RelayConfig) =
        RelayStats(
            name = config.name,
            direction = config.direction.name,
            from = if (config.direction == Direction.KAFKA_TO_BOOBLIK) config.kafkaTopic else config.booblikTopic,
            to = if (config.direction == Direction.KAFKA_TO_BOOBLIK) config.booblikTopic else config.kafkaTopic,
            relayed = relayed.get(),
            batches = batches.get(),
            position = position,
            restarts = restarts.get(),
            lastFailure = lastFailure,
            lastRecord = lastRecord,
        )
}

@Serializable
data class RelayStats(
    val name: String,
    val direction: String,
    val from: String,
    val to: String,
    val relayed: Long,
    val batches: Long,
    val position: Long?,
    val restarts: Long,
    val lastFailure: String?,
    val lastRecord: String?,
)

data class RelayConfig(
    val name: String,
    val direction: Direction,
    val brokerHost: String,
    val brokerPort: Int,
    val booblikTopic: String,
    val kafkaBootstrap: String,
    val kafkaTopic: String,
    val kafkaGroup: String,
    val stateDir: String,
    val httpPort: Int,
) {
    companion object {
        fun fromEnvironment() =
            RelayConfig(
                name = System.getenv("RELAY_NAME") ?: "relay",
                direction =
                    Direction.valueOf(
                        (System.getenv("RELAY_DIRECTION") ?: "KAFKA_TO_BOOBLIK")
                            .uppercase()
                            .replace('-', '_'),
                    ),
                brokerHost = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1",
                brokerPort = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092,
                booblikTopic = System.getenv("BOOBLIK_TOPIC") ?: "mirrored",
                kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP") ?: "kafka:9092",
                kafkaTopic = System.getenv("KAFKA_TOPIC") ?: "orders",
                // Kafka owns the position in one direction, and a consumer group is how it is
                // named. In the other direction there is no such thing and the relay keeps a file.
                kafkaGroup = System.getenv("KAFKA_GROUP") ?: "booblik-relay",
                stateDir = System.getenv("STATE_DIR") ?: "/var/lib/relay",
                httpPort = System.getenv("HTTP_PORT")?.toInt() ?: 8080,
            )
    }
}
