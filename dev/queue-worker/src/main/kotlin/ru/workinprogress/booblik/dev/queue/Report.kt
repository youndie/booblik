package ru.workinprogress.booblik.dev.queue

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.StartPosition
import java.net.InetSocketAddress

/**
 * What the claims log says about the queue, read out of the log itself.
 *
 * The point of reading it here rather than asking the workers: **it scales**. Thirty workers cannot
 * each publish an HTTP port, and asking thirty services for their own opinion of a shared log is a
 * worse measurement than replaying the log once. Everything M-103 needs is already written down —
 * one record per attempt, one per completion — so the log is both the subject and the instrument.
 *
 * Run it inside a worker container:
 *   docker compose exec worker-0 /opt/queue-worker/bin/queue-report
 */
object Report {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            val host = System.getenv("BOOBLIK_HOST") ?: "127.0.0.1"
            val port = System.getenv("BOOBLIK_PORT")?.toInt() ?: 9092
            val claimsTopic = System.getenv("BOOBLIK_CLAIMS_TOPIC") ?: "claims"
            val tasksTopic = System.getenv("BOOBLIK_TASKS_TOPIC") ?: "tasks"
            val address = InetSocketAddress(host, port)

            val claims = mutableListOf<ClaimRecord>()
            var state = ClaimState()
            var tasks = 0
            // Counted here rather than derived later. `attempts - finished` looks like the number of
            // lost races and is not: over a window, a task claimed before the window and finished
            // inside it adds to one count and not the other, and the subtraction can come out
            // **negative**. It did. A claim wins exactly when it becomes the lease, and that is
            // visible right here, one record at a time.
            var wins = 0

            BooblikSubscriber(address).use { subscriber ->
                // `replay` ends at the high watermark as it was when it started, which is exactly
                // what a report wants: a consistent cut, not a moving target.
                withTimeoutOrNull(30_000) {
                    subscriber
                        .replay(TopicName(tasksTopic), StartPosition.Earliest, listOf(PartitionId(0)))
                        .collect { tasks += it.records.size }
                }
                withTimeoutOrNull(30_000) {
                    subscriber
                        .replay(TopicName(claimsTopic), StartPosition.Earliest, listOf(PartitionId(0)))
                        .collect { batch ->
                            batch.records.forEachIndexed { index, record ->
                                val decoded = ClaimRecord.decode(record) ?: return@forEachIndexed
                                claims += decoded
                                val before = state.leases[decoded.task]
                                state = state.apply(decoded, batch.baseOffset.value + index + 1)
                                val after = state.leases[decoded.task]
                                if (decoded.type == ClaimRecord.CLAIM && after != null && after != before) wins++
                            }
                        }
                }
            }

            val attempts = claims.count { it.type == ClaimRecord.CLAIM }
            val completions = claims.count { it.type == ClaimRecord.DONE }
            val attemptsPerTask = claims.filter { it.type == ClaimRecord.CLAIM }.groupingBy { it.task }.eachCount()
            val workers = claims.map { it.worker }.distinct().sorted()
            val wonPerWorker = state.done.size

            println("# queue report: $tasksTopic and $claimsTopic")
            println("workers seen            ${workers.size}  (${workers.joinToString(", ")})")
            println("tasks published         $tasks")
            println("tasks finished          ${state.done.size}")
            println("tasks held right now    ${state.leases.size}")
            println("claim attempts          $attempts")
            println("completions written     $completions")

            // The number M-103 is about. Every attempt beyond the winning one is work that happened
            // only because nobody was there to hand the task out.
            val wasted = attempts - wins
            val share = if (attempts == 0) 0.0 else 100.0 * wasted / attempts
            println("claims that won         $wins")
            println("attempts that lost      $wasted  (${"%.1f".format(share)} % of all attempts)")

            val distribution =
                attemptsPerTask.values
                    .groupingBy { it }
                    .eachCount()
                    .toSortedMap()
            println(
                "attempts per task       " + distribution.entries.joinToString(", ") { "${it.key}→${it.value} tasks" },
            )

            // Completions must equal finished tasks. More means the same task was worked twice,
            // which is the failure the whole protocol exists to prevent.
            if (completions != state.done.size) {
                println(
                    "::error:: $completions completions for ${state.done.size} distinct tasks — a task was worked twice",
                )
                kotlin.system.exitProcess(1)
            }
            println("verdict                 every finished task was completed exactly once ($wonPerWorker)")
        }
}
