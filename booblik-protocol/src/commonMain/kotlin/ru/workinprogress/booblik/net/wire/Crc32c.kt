package ru.workinprogress.booblik.net.wire

/**
 * CRC-32C (Castagnoli) over [bytes], specified in `docs/api/protocol-wire.md` §7.2.
 *
 * `expect`, and this is the one function in the module that earns it. Decision Р8's last standing
 * objection to multiplatform was exactly this: on the JVM `java.util.zip.CRC32C` is an intrinsic
 * that compiles to a single instruction, and a hand-written loop in common code would cost every
 * byte a consumer reads on the platform where reading actually happens. `expect`/`actual` keeps the
 * intrinsic there and pays for the table only where there is nothing else — which is what
 * `ResponseDecoder`'s comment promised would happen once a native consumer had a buyer. M-138 is
 * that buyer.
 *
 * Returned as a signed `Int` because that is how the sum sits on the wire and in the segment: four
 * bytes, compared for equality, never ordered. Widening it to a `Long` is the mistake to avoid —
 * sign extension turns `0x82F63B78` into something no stored sum equals.
 */
internal expect fun crc32c(bytes: ByteArray): Int
