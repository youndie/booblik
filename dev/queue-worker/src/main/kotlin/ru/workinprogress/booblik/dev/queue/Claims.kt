package ru.workinprogress.booblik.dev.queue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A claim on a task, or the note that the task is finished.
 *
 * Both go into one partition of the `claims` topic, and that partition is the whole coordinator:
 * a partition is a total order, every reader sees the same order, so every reader reaches the same
 * verdict without anyone being asked.
 */
@Serializable
data class ClaimRecord(
    val type: String,
    val worker: String,
    val task: Long,
    /** The claimant's wall clock when it wrote this. See [ClaimState.apply] for what it is used for. */
    val at: Long = 0,
    val leaseMillis: Long = 0,
) {
    companion object {
        const val CLAIM = "claim"
        const val DONE = "done"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(record: ClaimRecord): ByteArray = json.encodeToString(record).toByteArray()

        fun decode(bytes: ByteArray): ClaimRecord? =
            runCatching { json.decodeFromString<ClaimRecord>(String(bytes)) }.getOrNull()
    }
}

/** Who holds a task, since when, and for how long. */
data class Lease(
    val worker: String,
    val at: Long,
    val leaseMillis: Long,
) {
    fun heldAt(instant: Long): Boolean = instant < at + leaseMillis
}

/**
 * What the claims log says, replayed in order.
 *
 * **The verdict is a pure function of the log, and that is the point of the whole design.** Whether
 * a claim wins is decided by comparing it against the lease in front of it using **the timestamp
 * written into the claim itself**, never the reader's own clock. So two workers replaying the same
 * records reach the same answer even if their clocks disagree — skew changes who *tries* to claim
 * and when, never who *won*.
 *
 * The alternative, judging expiry by the reader's `now()`, looks equivalent and is not: two workers
 * reading the same log a second apart would disagree about whether a lease had lapsed, and both
 * would believe they own the task.
 */
data class ClaimState(
    val consumedUpTo: Long = 0,
    val leases: Map<Long, Lease> = emptyMap(),
    val done: Set<Long> = emptySet(),
) {
    fun apply(
        record: ClaimRecord,
        nextOffset: Long,
    ): ClaimState =
        when (record.type) {
            ClaimRecord.DONE -> {
                copy(
                    consumedUpTo = nextOffset,
                    done = done + record.task,
                    leases = leases - record.task,
                )
            }

            ClaimRecord.CLAIM -> {
                val current = leases[record.task]
                val taken = record.task in done || (current != null && current.heldAt(record.at))
                if (taken) {
                    copy(consumedUpTo = nextOffset)
                } else {
                    copy(
                        consumedUpTo = nextOffset,
                        leases = leases + (record.task to Lease(record.worker, record.at, record.leaseMillis)),
                    )
                }
            }

            else -> {
                copy(consumedUpTo = nextOffset)
            }
        }

    /** Whether [worker] holds [task] under the claim it wrote at [at]. */
    fun holds(
        worker: String,
        task: Long,
        at: Long,
    ): Boolean = leases[task]?.let { it.worker == worker && it.at == at } == true

    /** Tasks this worker may try for: not finished, and not under a lease that has not lapsed. */
    fun claimable(
        tasks: Iterable<Long>,
        now: Long,
    ): List<Long> = tasks.filter { it !in done && leases[it]?.heldAt(now) != true }
}
