package ru.workinprogress.booblik

/**
 * Logical number of a record inside a partition. Monotonic, gap-free, assigned by the broker.
 *
 * The whole reason these are separate types is that [Offset] and [Position] are both `Long`-ish
 * and mixing them up produces a broker that silently reads the wrong bytes instead of failing.
 *
 * Note the cost, because it is easy to assume there is none: a `value class` is erased to its
 * carrier **only when the static type is the value class itself**. In a generic position it is
 * boxed — so `Map<Offset, Position>` allocates two objects per entry. That is why the index is a
 * [ru.workinprogress.booblik.storage.SparseOffsetIndex] over a `LongArray` and not a
 * `ConcurrentSkipListMap<Offset, Position>`; see docs/research §1.6.
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

/**
 * Byte position inside a single segment file.
 *
 * `Int`, not `Long`, and deliberately so: a segment is bounded by [Int.MAX_VALUE] bytes anyway
 * (both `MappedByteBuffer` and a single `transferTo` call are int-indexed — research §1.4, §1.5),
 * so a `Long` here would advertise a range the storage cannot serve.
 */
@JvmInline
value class Position(
    val value: Int,
) : Comparable<Position> {
    init {
        require(value >= 0) { "position must be non-negative, got $value" }
    }

    operator fun plus(delta: Int): Position = Position(value + delta)

    override fun compareTo(other: Position): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        val ZERO = Position(0)
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
