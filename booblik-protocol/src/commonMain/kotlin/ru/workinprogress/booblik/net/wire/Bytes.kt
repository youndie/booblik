package ru.workinprogress.booblik.net.wire

/**
 * A response ended before the fields it claims to contain.
 *
 * Its own type rather than an index-out-of-bounds, because the two mean different things to a
 * caller: a broker that restarted mid-frame is a connection problem to retry, and an exception
 * nobody declared coming out of a decoder reads as a bug in the client.
 */
class TruncatedFrameException(
    message: String,
) : IllegalStateException(message)

/**
 * Big-endian writes into a `ByteArray` of a size worked out in advance.
 *
 * `ByteArray` and index arithmetic rather than `ByteBuffer`, because this compiles for Kotlin/Native
 * as well as the JVM. It costs the JVM nothing: the finished array goes to `ByteBuffer.wrap`, which
 * makes a view and copies no bytes.
 */
internal class ByteWriter(
    size: Int,
) {
    val bytes = ByteArray(size)
    private var at = 0

    fun putByte(value: Byte) {
        bytes[at++] = value
    }

    fun putShort(value: Short) {
        val int = value.toInt()
        bytes[at++] = (int ushr 8).toByte()
        bytes[at++] = int.toByte()
    }

    fun putInt(value: Int) {
        bytes[at++] = (value ushr 24).toByte()
        bytes[at++] = (value ushr 16).toByte()
        bytes[at++] = (value ushr 8).toByte()
        bytes[at++] = value.toByte()
    }

    fun putLong(value: Long) {
        putInt((value ushr 32).toInt())
        putInt(value.toInt())
    }

    fun put(source: ByteArray) {
        source.copyInto(bytes, at)
        at += source.size
    }
}

/** Big-endian reads out of a `ByteArray`, refusing to run past the end. */
internal class ByteReader(
    private val bytes: ByteArray,
    private var at: Int = 0,
) {
    val remaining: Int get() = bytes.size - at

    var position: Int
        get() = at
        set(value) {
            at = value
        }

    fun short(): Short {
        require(2)
        val value = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        at += 2
        return value.toShort()
    }

    fun int(): Int {
        require(4)
        val value =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
        at += 4
        return value
    }

    fun long(): Long {
        // Locals, in order. Two positional reads in one expression would leave which half is the
        // high word depending on evaluation order — the same trap the response decoders avoid by
        // reading each field into a name first.
        val high = int().toLong() and 0xFFFFFFFFL
        val low = int().toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    fun bytes(count: Int): ByteArray {
        require(count)
        val slice = bytes.copyOfRange(at, at + count)
        at += count
        return slice
    }

    private fun require(count: Int) {
        if (remaining < count) {
            throw TruncatedFrameException("frame ends after $remaining bytes, needed $count more")
        }
    }
}
