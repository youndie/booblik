package io.github.youndie.booblik.dev.publisher

import io.github.youndie.booblik.PartitionId
import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.net.client.BooblikConnection
import io.github.youndie.booblik.net.client.Producer
import io.github.youndie.booblik.net.client.TopicHandle
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
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

    // Set by /pause, read by both producing loops. `AtomicBoolean` rather than `@Volatile` only
    // because it is shared with the HTTP routes below.
    val paused = AtomicBoolean(false)

    runBlocking {
        // Each producing loop holds its own connection and opens a new one when the broker goes away
        // (M-177). One connection opened here and shared used to be the whole of it: the broker
        // restarted, the next `send` failed, the loop's coroutine ended — and `/stats` went on
        // answering for a publisher that would never write again.
        scope.launch {
            connected(config, stats) { producer ->
                val topic = producer.topic(TopicName(config.topic))
                println(
                    "publisher: ${config.topic} has ${topic.partitions.size} partition(s), every ${config.intervalMillis} ms",
                )
                publishForever(topic, config, stats, paused)
            }
        }

        // The second layer's input, when it is asked for. Tasks go into one partition on purpose:
        // a queue exists so that any worker may take any task, and splitting them by partition
        // would be the first layer again under another name.
        config.tasksTopic?.let { name ->
            scope.launch { connected(config, stats) { producer -> publishTasks(producer, name, config, stats, paused) } }
            println("publisher: also writing tasks to $name")
        }

        embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
                get("/health") { call.respondText("ok") }
                get("/stats") { call.respond(stats.snapshot(config)) }

                /*
                 * A quiescent point for the checks, and the reason it exists is issue #12.
                 *
                 * `check.sh` compares what the publisher says it wrote against where each consumer
                 * has got to. Both numbers are true, and they are read a few milliseconds apart —
                 * so a record produced in between is a disagreement that means nothing, and the job
                 * failed at random on branches that do not touch `dev/` at all. Waiting longer does
                 * not fix it: there is no length of wait that makes two moving numbers agree.
                 *
                 * Stopping the container instead would take `/stats` away with the producing, and
                 * the check needs both. So production stops and the process stays.
                 */
                post("/pause") {
                    paused.set(true)
                    call.respondText("paused")
                }

                post("/resume") {
                    paused.set(false)
                    call.respondText("resumed")
                }
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
private suspend fun openConnection(
    config: PublisherConfig,
    scope: CoroutineScope,
): BooblikConnection {
    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    while (true) {
        try {
            return BooblikConnection(address, scope)
        } catch (failure: Exception) {
            println("publisher: broker at $address is not answering yet (${failure.message}), retrying")
            delay(1000)
        }
    }
}

/**
 * Runs [body] over a producer on a fresh connection, and again on a new one whenever it fails — the
 * broker restarting, a node drained. A record whose send failed is not counted: whether it reached
 * the log is unknown, and the stats say what was acknowledged.
 */
private suspend fun connected(
    config: PublisherConfig,
    stats: Stats,
    body: suspend (Producer) -> Unit,
) {
    while (true) {
        val connectionScope = CoroutineScope(SupervisorJob())
        try {
            val connection = openConnection(config, connectionScope)
            body(Producer(connection, connectionScope))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            println("publisher: lost the broker (${failure.message}), reconnecting")
            stats.reconnected()
            delay(1000)
        } finally {
            connectionScope.cancel()
        }
    }
}

private suspend fun publishForever(
    topic: TopicHandle,
    config: PublisherConfig,
    stats: Stats,
    paused: AtomicBoolean,
) {
    val users = List(config.users) { "user-${it + 1}" }
    while (true) {
        // Checked at the top of the loop, so a paused publisher has no record in flight: the check
        // that follows a pause compares a count that has stopped moving (issue #12).
        if (paused.get()) {
            delay(config.intervalMillis)
            continue
        }
        val user = users[Random.nextInt(users.size)]
        val key = user.toByteArray()
        // Asked before sending, and the answer is stable: the keyed partitioner is a pure function
        // of the key. Doing the same for an unkeyed record would be a bug — the round-robin
        // partitioner advances a counter, so asking would consume a slot and the records would
        // skip partitions.
        val partition = topic.partitionFor(key)

        val payload = """{"user":"$user","action":"${ACTIONS[Random.nextInt(ACTIONS.size)]}"}"""
        val offset = topic.send(payload.toByteArray(), key = key).await()

        stats.record(partition.value, offset.value, user)
        delay(config.intervalMillis)
    }
}

private suspend fun publishTasks(
    producer: Producer,
    topic: String,
    config: PublisherConfig,
    stats: Stats,
    paused: AtomicBoolean,
) {
    val name = TopicName(topic)
    var number = 0L
    while (true) {
        if (paused.get()) {
            delay(config.taskIntervalMillis)
            continue
        }
        producer
            .send(name, PartitionId(0), """{"task":"resize","n":$number}""".toByteArray())
            .await()
        stats.task()
        number++
        delay(config.taskIntervalMillis)
    }
}

private val ACTIONS = listOf("view", "click", "scroll", "purchase", "logout")

private class Stats {
    private val sent = AtomicLong()
    private val perPartition = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val lastOffset = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    @Volatile
    private var lastUser: String? = null

    private val tasks = AtomicLong()

    private val reconnects = AtomicLong()

    fun task() {
        tasks.incrementAndGet()
    }

    fun reconnected() {
        reconnects.incrementAndGet()
    }

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
            tasks = tasks.get(),
            reconnects = reconnects.get(),
        )
}

@Serializable
private data class PublisherStats(
    val topic: String,
    val sent: Long,
    val perPartition: Map<String, Long>,
    val lastOffset: Map<String, Long>,
    val lastUser: String?,
    val tasks: Long,
    val reconnects: Long,
)

private data class PublisherConfig(
    val brokerHost: String,
    val brokerPort: Int,
    val topic: String,
    val intervalMillis: Long,
    val users: Int,
    val httpPort: Int,
    val tasksTopic: String?,
    val taskIntervalMillis: Long,
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
                tasksTopic = System.getenv("BOOBLIK_TASKS_TOPIC")?.takeIf(String::isNotBlank),
                taskIntervalMillis = System.getenv("TASK_INTERVAL_MILLIS")?.toLong() ?: 700,
            )
    }
}
