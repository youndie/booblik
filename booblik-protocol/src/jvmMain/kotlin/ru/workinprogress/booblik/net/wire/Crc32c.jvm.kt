package ru.workinprogress.booblik.net.wire

/**
 * The JDK's own, and the reason this function is `expect` at all.
 *
 * `java.util.zip.CRC32C` has been there since JDK 9 and HotSpot intrinsifies it, so a consumer on
 * the JVM verifies at the speed of the hardware instruction rather than of a table lookup per byte.
 *
 * **Not `java.util.zip.CRC32`**, one line away in the same package and a different polynomial. Both
 * are called "CRC32", both return a plausible number, and a client using the wrong one rejects
 * every record it reads.
 */
internal actual fun crc32c(bytes: ByteArray): Int {
    val crc = java.util.zip.CRC32C()
    crc.update(bytes, 0, bytes.size)
    // `getValue()` is a `Long` holding an unsigned 32-bit value. The cast is what keeps the
    // comparison against the wire's signed int honest, rather than sign-extending one side of it.
    return crc.value.toInt()
}
