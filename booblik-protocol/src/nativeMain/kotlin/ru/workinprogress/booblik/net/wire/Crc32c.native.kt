package ru.workinprogress.booblik.net.wire

/**
 * The table-driven one, for platforms with nothing in the box.
 *
 * Built from the **reflected** polynomial `0x82F63B78`, because this shifts right; using
 * `0x1EDC6F41` with a right shift produces a sum that is stable, plausible and everywhere wrong.
 *
 * The 256-entry table is built once at first use. `Int` throughout with `ushr` for the shifts:
 * an arithmetic shift would drag the sign bit down through the table lookup, and Kotlin's `shr`
 * is arithmetic.
 */
private val table: IntArray by lazy {
    IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) (value ushr 1) xor POLYNOMIAL else value ushr 1
        }
        value
    }
}

/**
 * `0x82F63B78`, written as an unsigned literal converted rather than as the negative number it is.
 *
 * Not a style choice. The first version of this line carried a hand-derived `-0x7D644AC8`, which is
 * `0x829BB538` — a different polynomial, and one that produces a perfectly stable, perfectly wrong
 * sum for every input. The vectors caught it on the first run; nothing else would have, because
 * both `actual`s were self-consistent and only one of them was right.
 */
private val POLYNOMIAL = 0x82F63B78u.toInt()

internal actual fun crc32c(bytes: ByteArray): Int {
    var crc = -1 // 0xFFFFFFFF
    for (byte in bytes) {
        crc = table[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv() // the same as `xor 0xFFFFFFFF`, said in a way that has no width to get wrong
}
