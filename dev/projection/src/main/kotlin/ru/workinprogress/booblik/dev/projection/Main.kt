package ru.workinprogress.booblik.dev.projection

import io.ktor.http.HttpStatusCode
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
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A read model: state that exists only as a function of the log, and a query surface over it.
 *
 * This is the shape a log is actually for, and it is the only layer of the sample that uses
 * **both** halves of the subscription API for what they are:
 *
 *  * `replay()` **ends** — at the high watermark as it was when it started — so there is a moment
 *    when the view has caught up with history and can start answering queries;
 *  * `follow()` **does not**, so from that moment the view stays current.
 *
 * Joining them without a gap is what `RecordBatch.nextOffset` is for: replay reports where each
 * partition stopped, and follow starts there. Not `Latest`, which would skip whatever arrived while
 * the projection was building, and not `Earliest`, which would count history twice.
 *
 * Nothing is stored. See [Projection] for why persisting the position without the state would be
 * a silent corruption rather than an optimisation.
 */
fun main() {
    val config = ProjectionConfig.fromEnvironment()
    val projection = Projection()
    val progress = Progress()
    val scope = CoroutineScope(SupervisorJob())

    scope.launch { build(config, projection, progress) }

    runBlocking {
        embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
                get("/health") { call.respondText("ok") }
                get("/stats") { call.respond(progress.snapshot(config, projection)) }
                get("/top") {
                    val limit = call.request.queryParameters["n"]?.toIntOrNull() ?: 10
                    call.respond(projection.top(limit))
                }
                get("/user/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val view = projection.user(id)
                    if (view == null) {
                        // A user nobody has an event for is a 404 rather than an empty view: the
                        // difference between "did nothing" and "not in the log" is the projection's
                        // to report, not to smooth over.
                        call.respondText("no events for $id", status = HttpStatusCode.NotFound)
                    } else {
                        call.respond(view)
                    }
                }
            }
        }.start(wait = true)
    }
}

private suspend fun build(
    config: ProjectionConfig,
    projection: Projection,
    progress: Progress,
) {
    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    val topic = TopicName(config.topic)

    while (true) {
        try {
            BooblikSubscriber(address).use { subscriber ->
                val partitions = subscriber.partitionsOf(topic)
                println("projection: rebuilding ${config.topic} from the beginning of the live log, ${partitions.size} partition(s)")

                // History first. This flow completes, and that completion is the signal the query
                // surface waits for.
                val resumeFrom = ConcurrentHashMap<Int, Long>()
                subscriber.replay(topic, StartPosition.Earliest).collect { batch ->
                    batch.records.forEachIndexed { index, record ->
                        projection.apply(record, batch.baseOffset.value + index)
                    }
                    resumeFrom[batch.partition.value] = batch.nextOffset.value
                    progress.replayed.addAndGet(batch.records.size.toLong())
                }
                progress.replayComplete = true
                println("projection: replay done, ${projection.appliedCount()} events, ${projection.userCount()} users")

                // Then the tail, each partition from exactly where its replay stopped. One flow per
                // partition because a single `follow` takes one start position for all of them, and
                // here every partition has its own.
                val scope = CoroutineScope(SupervisorJob())
                partitions.forEach { partition ->
                    val from = resumeFrom[partition.value]
                    scope.launch {
                        subscriber
                            .follow(
                                topic,
                                from?.let { StartPosition.At(ru.workinprogress.booblik.Offset(it)) }
                                    ?: StartPosition.Earliest,
                                listOf(partition),
                            ).collect { batch ->
                                batch.records.forEachIndexed { index, record ->
                                    projection.apply(record, batch.baseOffset.value + index)
                                }
                                progress.followed.addAndGet(batch.records.size.toLong())
                                progress.positions[batch.partition.value] = batch.nextOffset.value
                                progress.live = true
                            }
                    }
                }
                // The launched followers own the connection; this coroutine has nothing left to do
                // but stay out of the way until something breaks.
                while (true) delay(60_000)
            }
        } catch (failure: Exception) {
            println("projection: lost the broker (${failure.message}), rebuilding")
            progress.rebuilds.incrementAndGet()
            progress.replayComplete = false
            progress.live = false
            delay(2000)
        }
    }
}

private class Progress {
    val replayed = AtomicLong()
    val followed = AtomicLong()
    val rebuilds = AtomicLong()
    val positions = ConcurrentHashMap<Int, Long>()

    @Volatile
    var replayComplete = false

    @Volatile
    var live = false

    fun snapshot(
        config: ProjectionConfig,
        projection: Projection,
    ) = ProjectionStats(
        topic = config.topic,
        replayComplete = replayComplete,
        live = live,
        fromReplay = replayed.get(),
        fromFollow = followed.get(),
        applied = projection.appliedCount(),
        skipped = projection.skippedCount(),
        users = projection.userCount(),
        eventsAcrossUsers = projection.eventsAcrossUsers(),
        positions = positions.toSortedMap().mapKeys { it.key.toString() },
        rebuilds = rebuilds.get(),
    )
}

@Serializable
private data class ProjectionStats(
    val topic: String,
    val replayComplete: Boolean,
    val live: Boolean,
    val fromReplay: Long,
    val fromFollow: Long,
    val applied: Long,
    val skipped: Long,
    val users: Int,
    val eventsAcrossUsers: Long,
    val positions: Map<String, Long>,
    val rebuilds: Long,
)

private data class ProjectionConfig(
    val brokerHost: String,
    val brokerPort: Int,
    val topic: String,
    val httpPort: Int,
) {
    companion object {
        fun fromEnvironment() =
            ProjectionConfig(
                brokerHost = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1",
                brokerPort = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092,
                topic = System.getenv("BOOBLIK_TOPIC") ?: "events",
                httpPort = System.getenv("HTTP_PORT")?.toInt() ?: 8080,
            )
    }
}
