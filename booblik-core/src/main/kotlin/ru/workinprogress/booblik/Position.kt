package ru.workinprogress.booblik

/**
 * Byte position inside a single segment file.
 *
 * `Int`, not `Long`, and deliberately so: a segment is bounded by [Int.MAX_VALUE] bytes anyway
 * (both `MappedByteBuffer` and a single `transferTo` call are int-indexed — research §1.4, §1.5),
 * so a `Long` here would advertise a range the storage cannot serve.
 *
 * The only id that stayed in the core when M-134 moved the rest to `:booblik-protocol`. The others
 * — [Offset], [TopicName], [PartitionId] — appear on the wire and therefore have to be something a
 * client can name. This one never does: it is where a record sits in a file, which is nobody's
 * business but the broker's, and a `value class` is erased anyway so keeping it here costs nothing.
 *
 * Mixing it up with [Offset] is what these types exist to prevent — both are number-ish, and the
 * result of confusing them is a broker that silently reads the wrong bytes instead of failing.
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
