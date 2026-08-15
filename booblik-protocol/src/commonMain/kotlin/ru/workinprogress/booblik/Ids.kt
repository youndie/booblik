package ru.workinprogress.booblik

// Explicit, because `kotlin.jvm.*` is a default import only when compiling for the JVM. Without it
// these files compile on the JVM and fail on every native target — the exact shape of mistake that
// makes "it builds" mean nothing in a multiplatform module.
import kotlin.jvm.JvmInline

/**
 * Logical number of a record inside a partition. Monotonic, gap-free, assigned by the broker.
 *
 * The whole reason these are separate types is that offsets and byte positions are both `Long`-ish
 * and mixing them up produces a broker that silently reads the wrong bytes instead of failing.
 *
 * Note the cost, because it is easy to assume there is none: a `value class` is erased to its
 * carrier **only when the static type is the value class itself**. In a generic position it is
 * boxed — so `Map<Offset, …>` allocates per entry. That is why the storage index is a `LongArray`
 * and not a map keyed by this type; see docs/research §1.6.
 *
 * Lives here rather than in `:booblik-core` because it goes **on the wire**, and the wire is what
 * a client and a broker have to agree on. `Position` stayed in the core for the mirror-image
 * reason: it is a byte offset inside a segment file and never appears in a request or a response.
 */
@JvmInline
value class Offset(
    val value: Long,
) : Comparable<Offset> {
    init {
        require(value >= 0) { "offset must be non-negative, got $value" }
    }

    operator fun inc(): Offset = Offset(value + 1)

    operator fun plus(delta: Long): Offset = Offset(value + delta)

    operator fun minus(other: Offset): Long = value - other.value

    override fun compareTo(other: Offset): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        val ZERO = Offset(0)
    }
}

@JvmInline
value class TopicName(
    val value: String,
) {
    init {
        require(value.isNotEmpty()) { "topic name must not be empty" }
        require(value.length <= MAX_LENGTH) { "topic name must be at most $MAX_LENGTH chars" }
        require(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            "topic name may contain only [a-zA-Z0-9._-], got '$value'"
        }
    }

    override fun toString(): String = value

    companion object {
        /** Bounded because the topic name goes on the wire with a `u16` length prefix. */
        const val MAX_LENGTH = 249
    }
}

@JvmInline
value class PartitionId(
    val value: Int,
) {
    init {
        require(value >= 0) { "partition id must be non-negative, got $value" }
    }

    override fun toString(): String = value.toString()
}
