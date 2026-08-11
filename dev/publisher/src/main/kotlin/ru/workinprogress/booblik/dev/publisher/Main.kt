package ru.workinprogress.booblik.dev.publisher

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
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.TopicHandle
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Writes an event every so often and says what it wrote.
 *
 * Every record carries a **key** — the user it belongs to — and the key is what picks the
 * partition. That is not decoration: it is the only reason the three consumers can process
 * different users at the same time and still see each user's events in order. The key never
 * reaches the broker; `TopicHandle` hashes it and sends a partition number.
 */
fun main() {
    val config = PublisherConfig.fromEnvironment()
    val scope = CoroutineScope(SupervisorJob())
    val stats = Stats()

    runBlocking {
        val connection = openConnection(config)
        val producer = Producer(connection, scope)
        val topic = producer.topic(TopicName(config.topic))
        println("publisher: ${config.topic} has ${topic.partitions.size} partition(s), every ${config.intervalMillis} ms")

        scope.launch { publishForever(topic, config, stats) }

        embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
                get("/health") { call.respondText("ok") }
                get("/stats") { call.respond(stats.snapshot(config)) }
            }
        }.start(wait = true)
    }
}

/**
 * Retries until the broker answers.
 *
 * `depends_on: service_healthy` in compose already waits for the broker's own health check, so
 * this should never loop in practice. It is here because "should never" and "does not" are
 * different claims, and a sample that dies on a startup race teaches the wrong lesson about the
 * broker.
 */
private suspend fun openConnection(config: PublisherConfig): BooblikConnection {
    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    while (true) {
        try {
            return BooblikConnection(address, CoroutineScope(SupervisorJob()))
        } catch (failure: Exception) {
            println("publisher: broker at $address is not answering yet (${failure.message}), retrying")
            delay(1000)
        }
    }
}

private suspend fun publishForever(
    topic: TopicHandle,
    config: PublisherConfig,
    stats: Stats,
) {
    val users = List(config.users) { "user-${it + 1}" }
    while (true) {
        val user = users[Random.nextInt(users.size)]
        val key = user.toByteArray()
        // Asked before sending, and the answer is stable: `ByKeyHash` is a pure function of the
        // key. Doing the same for an unkeyed record would be a bug — the round-robin partitioner
        // advances a counter, so asking would consume a slot and the records would skip partitions.
        val partition = topic.partitionFor(key)

        val payload = """{"user":"$user","action":"${ACTIONS[Random.nextInt(ACTIONS.size)]}"}"""
        val offset = topic.send(payload.toByteArray(), key = key).await()

        stats.record(partition.value, offset.value, user)
        delay(config.intervalMillis)
    }
}

private val ACTIONS = listOf("view", "click", "scroll", "purchase", "logout")

private class Stats {
    private val sent = AtomicLong()
    private val perPartition = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastOffset = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    @Volatile
    private var lastUser: String? = null

    fun record(
        partition: Int,
        offset: Long,
        user: String,
    ) {
        sent.incrementAndGet()
        perPartition.merge(partition, 1L, Long::plus)
        lastOffset[partition] = offset
        lastUser = user
    }

    fun snapshot(config: PublisherConfig) =
        PublisherStats(
            topic = config.topic,
            sent = sent.get(),
            perPartition = perPartition.toSortedMap().mapKeys { it.key.toString() },
            lastOffset = lastOffset.toSortedMap().mapKeys { it.key.toString() },
            lastUser = lastUser,
        )
}

@Serializable
private data class PublisherStats(
    val topic: String,
    val sent: Long,
    val perPartition: Map<String, Long>,
    val lastOffset: Map<String, Long>,
    val lastUser: String?,
)

private data class PublisherConfig(
    val brokerHost: String,
    val brokerPort: Int,
    val topic: String,
    val intervalMillis: Long,
    val users: Int,
    val httpPort: Int,
) {
    companion object {
        fun fromEnvironment() =
            PublisherConfig(
                brokerHost = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1",
                brokerPort = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092,
                topic = System.getenv("BOOBLIK_TOPIC") ?: "events",
                intervalMillis = System.getenv("PUBLISH_INTERVAL_MILLIS")?.toLong() ?: 1000,
                users = System.getenv("PUBLISH_USERS")?.toInt() ?: 9,
                httpPort = System.getenv("HTTP_PORT")?.toInt() ?: 8080,
            )
    }
}
