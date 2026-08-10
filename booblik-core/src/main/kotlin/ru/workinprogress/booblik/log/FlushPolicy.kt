package ru.workinprogress.booblik.log

/**
 * How often the writer forces a barrier on its own, independently of what producers ask for.
 *
 * ## What this buys, and what it very deliberately does not
 *
 * It bounds the **window of loss**: with `everyMillis = 100`, at most the last hundred milliseconds
 * of accepted writes can vanish in a power cut. That is a useful thing to be able to state.
 *
 * It does **not** make [AckPolicy.WRITTEN] durable. A producer told "written" still got told that
 * before any barrier ran, and no amount of background flushing changes what it was told at the time
 * it was told. Only [AckPolicy.FORCED] waits for the barrier. Confusing the two is the single
 * easiest way to believe a system is safer than it is — the whole M-24 detour happened because a
 * call that looked like a barrier was not one.
 *
 * ## Why both a count and a time
 *
 * A count alone leaves an idle broker holding unflushed data indefinitely: the last few records
 * before the traffic stopped are exactly the ones nobody flushes. A time alone forces on a schedule
 * that ignores load, so a burst can put far more at risk between two ticks than intended. Whichever
 * comes first wins.
 *
 * [Disabled] is the default, and it is honest rather than reckless: the OS still writes back on its
 * own, and a policy here would pretend to a guarantee whose size nobody has chosen.
 */
data class FlushPolicy(
    /** Force after this many records since the last barrier. Null disables the count trigger. */
    val everyRecords: Long? = null,
    /** Force this long after the last barrier. Null disables the time trigger. */
    val everyMillis: Long? = null,
) {
    init {
        require(everyRecords == null || everyRecords > 0) { "everyRecords must be positive" }
        require(everyMillis == null || everyMillis > 0) { "everyMillis must be positive" }
    }

    val isEnabled: Boolean get() = everyRecords != null || everyMillis != null

    companion object {
        val Disabled = FlushPolicy()
    }
}
