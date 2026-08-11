package ru.workinprogress.booblik.dev.queue

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.Producer
import ru.workinprogress.booblik.net.client.StartPosition
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * A worker in a task queue built **on top of** the log rather than inside the broker.
 *
 * booblik has no delivery state: no per-record lock, no acknowledgement, no redelivery. That is a
 * decision rather than a gap (docs/research/research-usecases.md, Р11) — Kafka needed a second
 * kind of group, with per-record acquisition locks, to serve this shape of work.
 *
 * So the queue is a protocol. Workers write claims into one partition of a `claims` topic, and the
 * **order of that partition is the arbiter**: the first claim on a free task wins, and everybody
 * reading the log agrees because there is only one order to read.
 *
 * What this does not give, stated plainly rather than discovered later:
 *
 *  * **at-least-once.** A worker that stalls past its lease wakes up and finishes a task somebody
 *    else has already taken. There is no fencing — nothing rejects the late result — because that
 *    would need a party that knows which lease is current, which is the coordinator this design
 *    exists to avoid.
 *  * **the claims log grows** with two records per task and lives on retention alone. Retention
 *    shorter than a lease is a silent bug: the claim disappears, the task looks free, and a second
 *    worker takes it while the first is still working.
 *  * **every worker reads every claim.** Traffic is workers x tasks. Fine for three, not for three
 *    hundred.
 *  * **taking a task costs a round trip** — write the claim, then read the log until it comes back.
 *    This queue is meant to be legible, not fast. M-103 measures what it costs.
 */
fun main() {
    val config = WorkerConfig.fromEnvironment()
    val stats = Stats()
    val scope = CoroutineScope(SupervisorJob())
    val claims = MutableStateFlow(ClaimState())
    val tasks = MutableStateFlow<Map<Long, String>>(emptyMap())

    scope.launch { followClaims(config, claims) }
    scope.launch { followTasks(config, tasks) }
    scope.launch { work(config, claims, tasks, stats) }

    runBlocking {
        embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
                get("/health") { call.respondText("ok") }
                get("/stats") { call.respond(stats.snapshot(config, claims.value, tasks.value.size)) }
                // One task, as this worker's replay of the claims log sees it. The redistribution
                // check needs to ask about a **particular** task rather than about a count: after a
                // worker is killed, the question is whether the task it was holding got finished by
                // somebody else, and a counter cannot answer that.
                get("/task/{offset}") {
                    val offset = call.parameters["offset"]?.toLongOrNull()
                    val state = claims.value
                    if (offset == null) {
                        call.respondText("offset must be a number", status = io.ktor.http.HttpStatusCode.BadRequest)
                    } else {
                        val lease = state.leases[offset]
                        call.respond(
                            TaskState(
                                task = offset,
                                done = offset in state.done,
                                heldBy = lease?.worker,
                                heldAt = lease?.at,
                                known = offset in tasks.value,
                            ),
                        )
                    }
                }
            }
        }.start(wait = true)
    }
}

private suspend fun followClaims(
    config: WorkerConfig,
    claims: MutableStateFlow<ClaimState>,
) {
    follow(config, config.claimsTopic) { batch ->
        claims.update { state ->
            var next = state
            batch.records.forEachIndexed { index, record ->
                val decoded = ClaimRecord.decode(record)
                val offset = batch.baseOffset.value + index + 1
                next = if (decoded == null) next.copy(consumedUpTo = offset) else next.apply(decoded, offset)
            }
            next
        }
    }
}

private suspend fun followTasks(
    config: WorkerConfig,
    tasks: MutableStateFlow<Map<Long, String>>,
) {
    follow(config, config.tasksTopic) { batch ->
        tasks.update { known ->
            known + batch.records.mapIndexed { index, record -> (batch.baseOffset.value + index) to String(record) }
        }
    }
}

private suspend fun follow(
    config: WorkerConfig,
    topic: String,
    handle: suspend (ru.workinprogress.booblik.net.client.RecordBatch) -> Unit,
) {
    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    while (true) {
        try {
            // From the beginning of the live log, every time. The state here is derived, not owned:
            // a worker that restarts rebuilds it by replaying, which is the same thing the log does
            // for everyone else. There is nothing to checkpoint.
            BooblikSubscriber(address).use { subscriber ->
                subscriber
                    .follow(TopicName(topic), StartPosition.Earliest, listOf(PartitionId(0)))
                    .collect { handle(it) }
            }
        } catch (failure: Exception) {
            println("worker: lost $topic (${failure.message}), reconnecting")
            delay(1000)
        }
    }
}

private suspend fun work(
    config: WorkerConfig,
    claims: MutableStateFlow<ClaimState>,
    tasks: MutableStateFlow<Map<Long, String>>,
    stats: Stats,
) {
    val address = InetSocketAddress(config.brokerHost, config.brokerPort)
    val scope = CoroutineScope(SupervisorJob())
    val producer = Producer(BooblikConnection(address, scope), scope)
    val claimsTopic = TopicName(config.claimsTopic)

    while (true) {
        val now = System.currentTimeMillis()
        // `first` is the obvious choice and the expensive one: every idle worker sees the same
        // free task at the head of the queue and claims it at the same moment, so attempts per task
        // climb towards the number of workers. `random` spreads the attention, at the cost of
        // giving up the order tasks were published in. M-103 measures both.
        val claimable = claims.value.claimable(tasks.value.keys.sorted(), now)
        val candidate = if (config.pickRandom) claimable.randomOrNull() else claimable.firstOrNull()
        if (candidate == null) {
            delay(config.idleMillis)
            continue
        }

        val claim = ClaimRecord(ClaimRecord.CLAIM, config.name, candidate, now, config.leaseMillis)
        val offset =
            producer
                .send(claimsTopic, PartitionId(0), ClaimRecord.encode(claim))
                .await()
        stats.attempts.incrementAndGet()

        // Read our own claim back before deciding anything. Until it has come round, the log has
        // not answered — and the log is the only thing entitled to answer.
        // The round trip that taking a task costs: the claim is written, and nothing may be decided
        // until it has come back round the log. This is the number M-103 exists for.
        val began = System.nanoTime()
        val settled =
            withTimeoutOrNull(config.settleTimeoutMillis) {
                claims.first { it.consumedUpTo > offset.value }
            }
        if (settled == null) {
            stats.timeouts.incrementAndGet()
            continue
        }
        stats.observeClaimLatency((System.nanoTime() - began) / 1_000)

        if (!settled.holds(config.name, candidate, now)) {
            stats.lost.incrementAndGet()
            continue
        }

        stats.won.incrementAndGet()
        stats.current = candidate
        delay(config.workMillis)
        producer
            .send(claimsTopic, PartitionId(0), ClaimRecord.encode(ClaimRecord(ClaimRecord.DONE, config.name, candidate)))
            .await()
        stats.current = null
        stats.finished.incrementAndGet()
    }
}

private class Stats {
    val attempts = AtomicLong()
    val won = AtomicLong()
    val lost = AtomicLong()
    val finished = AtomicLong()
    val timeouts = AtomicLong()

    @Volatile
    var current: Long? = null

    // Bounded on purpose: a sample that runs for days must not grow a list until it is the
    // interesting part of its own memory profile.
    private val claimLatenciesMicros = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    fun observeClaimLatency(micros: Long) {
        claimLatenciesMicros.addLast(micros)
        while (claimLatenciesMicros.size > 10_000) claimLatenciesMicros.pollFirst()
    }

    private fun percentile(share: Double): Long {
        val sorted = claimLatenciesMicros.toLongArray().sortedArray()
        if (sorted.isEmpty()) return 0
        return sorted[((sorted.size - 1) * share).toInt()]
    }

    fun snapshot(
        config: WorkerConfig,
        state: ClaimState,
        knownTasks: Int,
    ) = WorkerStats(
        name = config.name,
        attempts = attempts.get(),
        won = won.get(),
        lost = lost.get(),
        finished = finished.get(),
        timeouts = timeouts.get(),
        current = current,
        knownTasks = knownTasks,
        doneTasks = state.done.size,
        heldByAnyone = state.leases.size,
        claimLatencyMicros =
            Percentiles(
                p50 = percentile(0.50),
                p90 = percentile(0.90),
                p99 = percentile(0.99),
                samples = claimLatenciesMicros.size,
            ),
    )
}

@Serializable
private data class WorkerStats(
    val name: String,
    val attempts: Long,
    val won: Long,
    val lost: Long,
    val finished: Long,
    val timeouts: Long,
    val current: Long?,
    val knownTasks: Int,
    val doneTasks: Int,
    val heldByAnyone: Int,
    val claimLatencyMicros: Percentiles,
)

@Serializable
private data class Percentiles(
    val p50: Long,
    val p90: Long,
    val p99: Long,
    val samples: Int,
)

@Serializable
private data class TaskState(
    val task: Long,
    val done: Boolean,
    val heldBy: String?,
    val heldAt: Long?,
    val known: Boolean,
)

private data class WorkerConfig(
    val name: String,
    val brokerHost: String,
    val brokerPort: Int,
    val tasksTopic: String,
    val claimsTopic: String,
    val leaseMillis: Long,
    val workMillis: Long,
    val idleMillis: Long,
    val settleTimeoutMillis: Long,
    val httpPort: Int,
    val pickRandom: Boolean,
) {
    companion object {
        fun fromEnvironment() =
            WorkerConfig(
                // Falls back to the container id, not to a constant. Ownership of a claim is
                // (worker, timestamp), so two workers sharing a name could both believe they hold
                // the same task — which is exactly what `docker compose --scale` would produce,
                // silently, and only under the load the measurement is about.
                name = System.getenv("WORKER_NAME") ?: System.getenv("HOSTNAME") ?: "worker",
                brokerHost = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1",
                brokerPort = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092,
                tasksTopic = System.getenv("BOOBLIK_TASKS_TOPIC") ?: "tasks",
                claimsTopic = System.getenv("BOOBLIK_CLAIMS_TOPIC") ?: "claims",
                // Comfortably longer than the work below. A lease shorter than the work is how a
                // task gets handed to a second worker while the first is still on it.
                leaseMillis = System.getenv("WORKER_LEASE_MILLIS")?.toLong() ?: 30_000,
                workMillis = System.getenv("WORKER_WORK_MILLIS")?.toLong() ?: 400,
                idleMillis = System.getenv("WORKER_IDLE_MILLIS")?.toLong() ?: 200,
                settleTimeoutMillis = System.getenv("WORKER_SETTLE_TIMEOUT_MILLIS")?.toLong() ?: 10_000,
                httpPort = System.getenv("HTTP_PORT")?.toInt() ?: 8080,
                pickRandom = System.getenv("WORKER_PICK")?.equals("random", ignoreCase = true) == true,
            )
    }
}
