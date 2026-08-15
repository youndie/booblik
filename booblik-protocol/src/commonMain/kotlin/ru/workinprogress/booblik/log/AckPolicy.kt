package ru.workinprogress.booblik.log

/**
 * How long a producer waits before it is told its records were accepted.
 *
 * The three levels are not "fast, medium, safe" — they are three different promises, and only one
 * of them is a durability promise:
 *
 * * [NONE] promises nothing. There is no reply at all, so there is nothing to wait for and nothing
 *   to allocate. It exists to give benchmarks an upper bound and to be the honest name for
 *   fire-and-forget.
 * * [WRITTEN] promises the bytes are in the log and the offset is final. It does **not** promise
 *   they survive a power loss — that needs the barrier `SegmentWriter.force` performs, and this
 *   level is answered before any barrier.
 * * [FORCED] promises durability, and costs a disk barrier: about 4 ms on the reference host, or
 *   250 batches per second per producer. That number is why the broker's writer groups everyone
 *   already queued into one barrier instead of one per request.
 *
 * On the wire as a single byte, in this order. The values must not be renumbered.
 */
enum class AckPolicy {
    NONE,
    WRITTEN,
    FORCED,
}
