package ru.workinprogress.booblik.dev.consumer

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
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.StartPosition
import ru.workinprogress.booblik.net.client.checkpointing
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import ru.workinprogress.booblik.dev.common.FileOffsetStore
import kotlin.io.path.Path

/**
 * Reads one partition and remembers where it stopped.
 *
 * One partition per service is a static assignment, written in the environment. It is also, minus
 * the automatic rebalancing, exactly what a Kafka consumer group gives you: a partition is read by
 * exactly one consumer in the group. What booblik has no answer for is a **failed** consumer —
 * nobody takes its partition over. That is the subject of the second layer of this sample, and it
 * is a protocol on top of the log rather than a feature of the broker
 * (docs/research/research-usecases.md).
 */
fun main() {
    val config = ConsumerConfig.fromEnvironment()
    val stats = Stats()
    val scope = CoroutineScope(SupervisorJob())

    scope.launch { consumeForever(config, stats) }

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

private suspend fun consumeForever(
    config: ConsumerConfig,
    stats: Stats,
) {
    val store = FileOffsetStore(Path(config.stateDir))
    val topic = TopicName(config.topic)
    val partition = PartitionId(config.partition)

    // Where a restart carries on from. `Earliest` is the start of the **live** log rather than
    // zero — retention moves it — so a consumer that has never run reads everything still kept
    // rather than failing on records that were deleted last week.
    val saved = store.load(topic, partition)
    val start = saved?.let { StartPosition.At(it) } ?: StartPosition.Earliest
    stats.resumedFrom = saved?.value
    println("consumer ${config.name}: partition ${config.partition}, starting from ${saved?.value ?: "the beginning of the live log"}")

    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    while (true) {
        try {
            BooblikSubscriber(address).use { subscriber ->
                subscriber
                    .follow(topic, start, listOf(partition))
                    // The save happens **after** the collector below has run, and that ordering is
                    // the entire delivery guarantee: at-least-once. A crash between handling and
                    // saving replays the batch; saving first would skip it instead.
                    .checkpointing(store)
                    .collect { batch ->
                        batch.records.forEach { record -> handle(String(record), stats) }
                        stats.observe(batch.nextOffset.value, batch.lag)
                    }
            }
        } catch (failure: Exception) {
            // The broker going away is not the end of a consumer. It keeps its position, so
            // reconnecting costs nothing but the reconnect.
            println("consumer ${config.name}: lost the broker (${failure.message}), reconnecting")
            stats.reconnects.incrementAndGet()
            delay(1000)
        }
    }
}

/** Stands in for the work. Slow enough to be visible, fast enough not to fall behind. */
private suspend fun handle(
    record: String,
    stats: Stats,
) {
    delay(50)
    stats.handled.incrementAndGet()
    stats.lastRecord = record
}

private class Stats {
    val handled = AtomicLong()
    val reconnects = AtomicLong()

    @Volatile
    var lastRecord: String? = null

    @Volatile
    var resumedFrom: Long? = null

    @Volatile
    private var position: Long = 0

    @Volatile
    private var lag: Long = 0

    fun observe(
        nextOffset: Long,
        currentLag: Long,
    ) {
        position = nextOffset
        lag = currentLag
    }

    fun snapshot(config: ConsumerConfig) =
        ConsumerStats(
            name = config.name,
            topic = config.topic,
            partition = config.partition,
            handled = handled.get(),
            position = position,
            lag = lag,
            resumedFrom = resumedFrom,
            reconnects = reconnects.get(),
            lastRecord = lastRecord,
        )
}

@Serializable
private data class ConsumerStats(
    val name: String,
    val topic: String,
    val partition: Int,
    val handled: Long,
    val position: Long,
    val lag: Long,
    val resumedFrom: Long?,
    val reconnects: Long,
    val lastRecord: String?,
)

private data class ConsumerConfig(
    val name: String,
    val brokerHost: String,
    val brokerPort: Int,
    val topic: String,
    val partition: Int,
    val stateDir: String,
    val httpPort: Int,
) {
    companion object {
        fun fromEnvironment() =
            ConsumerConfig(
                name = System.getenv("CONSUMER_NAME") ?: "consumer",
                brokerHost = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1",
                brokerPort = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092,
                topic = System.getenv("BOOBLIK_TOPIC") ?: "events",
                partition = System.getenv("BOOBLIK_PARTITION")?.toInt() ?: 0,
                stateDir = System.getenv("STATE_DIR") ?: "/var/lib/consumer",
                httpPort = System.getenv("HTTP_PORT")?.toInt() ?: 8080,
            )
    }
}
