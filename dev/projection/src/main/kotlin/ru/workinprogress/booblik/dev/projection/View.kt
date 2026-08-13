package ru.workinprogress.booblik.dev.projection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One event as the publisher writes it. Unknown fields are ignored: a projection outlives schemas. */
@Serializable
data class Event(
    val user: String,
    val action: String,
)

@Serializable
data class UserView(
    val user: String,
    val events: Long,
    val actions: Map<String, Long>,
    val lastAction: String?,
    val lastOffset: Long,
)

/**
 * State derived entirely from the log, held in memory, and thrown away on exit.
 *
 * That last part is the design rather than laziness. A projection whose state is in memory must
 * **not** persist its position: a restart would then resume at a late offset with an empty state,
 * and every query afterwards would answer confidently from a view missing everything before that
 * offset. Nothing would crash and nothing would say so. Persist both or neither — this one persists
 * neither, and rebuilds by replaying, which is the cheapest correct answer while the log is small
 * enough to re-read.
 *
 * Order matters only inside a partition, and that is enough here **because of what the publisher
 * does**: every event carries the user as its key, so one user's events are all in one partition
 * and arrive in order. Take the key away and this projection would still count correctly and would
 * report `lastAction` at random.
 */
class Projection {
    private val users = ConcurrentHashMap<String, UserView>()
    private val applied = AtomicLong()
    private val skipped = AtomicLong()

    fun apply(
        record: ByteArray,
        offset: Long,
    ) {
        val event = decode(record)
        if (event == null) {
            // A record the projection cannot read is not a reason to stop reading the log. It is
            // counted, because silently ignoring input is how a projection quietly goes wrong.
            skipped.incrementAndGet()
            return
        }
        users.compute(event.user) { _, previous ->
            val actions = (previous?.actions ?: emptyMap()).toMutableMap()
            actions.merge(event.action, 1L, Long::plus)
            UserView(
                user = event.user,
                events = (previous?.events ?: 0) + 1,
                actions = actions,
                lastAction = event.action,
                lastOffset = offset,
            )
        }
        applied.incrementAndGet()
    }

    fun user(id: String): UserView? = users[id]

    fun top(limit: Int): List<UserView> = users.values.sortedByDescending { it.events }.take(limit)

    fun appliedCount(): Long = applied.get()

    fun skippedCount(): Long = skipped.get()

    fun userCount(): Int = users.size

    /** The sum every check needs: a view that disagrees with its own inputs is the failure here. */
    fun eventsAcrossUsers(): Long = users.values.sumOf { it.events }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decode(record: ByteArray): Event? = runCatching { json.decodeFromString<Event>(String(record)) }.getOrNull()
    }
}
