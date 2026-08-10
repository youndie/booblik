package ru.workinprogress.booblik.log

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.storage.Log

/**
 * A [Log] that counts what was asked of it instead of writing anything.
 *
 * Two of the properties this test suite cares about — that `WRITTEN` never pays for a barrier, and
 * that group commit collapses many barriers into one — are about the *number of calls*, and a real
 * segment cannot report that. [forceMillis] lets a test make the barrier slow enough that grouping
 * is the expected outcome rather than a lucky one.
 *
 * No synchronisation, deliberately: only the writer coroutine touches it, and if that ever stops
 * being true these counters will disagree with reality, which is the failure we want.
 */
class CountingLog(
    private val forceMillis: Long = 0,
) : Log {
    var appendCount: Int = 0
        private set

    var forceCount: Int = 0
        private set

    override var nextOffset: Offset = Offset.ZERO
        private set

    override fun hasRoomFor(payloadSize: Int): Boolean = true

    override fun append(
        payload: ByteArray,
        from: Int,
        length: Int,
    ): Offset {
        val assigned = nextOffset
        appendCount += 1
        nextOffset = assigned.inc()
        return assigned
    }

    override fun force() {
        forceCount += 1
        if (forceMillis > 0) Thread.sleep(forceMillis)
    }
}
