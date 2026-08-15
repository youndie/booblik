package ru.workinprogress.booblik.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.openjdk.jmh.annotations.OperationsPerInvocation
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.net.client.ResponseReader
import ru.workinprogress.booblik.net.wire.ResponseDecoder
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32C

/**
 * M-140: what a JVM reader pays for the shared FETCH decoder.
 *
 * There are two of them, and that is the thing to settle rather than tolerate:
 *
 * * [reader] — `ResponseReader.fetch` in `:booblik-client`, over `ByteBuffer`. Older, JVM-only;
 * * [decoder] — `ResponseDecoder.fetch` in `:booblik-protocol`, over `ByteArray` and index
 *   arithmetic, shared with Kotlin/Native since M-138.
 *
 * They read the same bytes and both verify every checksum with the same intrinsic. The question is
 * not which reads better — it is whether merging them costs the platform where reading actually
 * happens, and that is a number rather than an opinion. Written before the merge, because a
 * measurement taken afterwards decides nothing.
 *
 * [recordSize] is a parameter because the two differ only in per-record bookkeeping: at 8 KiB the
 * checksum dominates and any difference should vanish, at 64 B it is as visible as it ever gets.
 * A response is a fixed 64 records either way, so the throughput axis is records rather than
 * responses — [OperationsPerInvocation] does that division.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class FetchDecodeBenchmark {
    @Param("64", "1024", "8192")
    var recordSize: Int = 1024

    private lateinit var frame: ByteArray

    @Setup
    fun build() {
        // A whole FETCH response as the socket delivers it: correlationId, errorCode,
        // highWatermark, payloadBytes, then records exactly as the segment holds them.
        val payload = ByteArray(recordSize) { it.toByte() }
        val crc = CRC32C()
        crc.update(payload, 0, payload.size)
        val checksum = crc.value.toInt()

        val record = ByteArray(RECORD_HEADER + recordSize)
        writeInt(record, 0, recordSize)
        writeInt(record, 4, checksum)
        payload.copyInto(record, RECORD_HEADER)

        val body = ByteArray(RECORDS * record.size)
        repeat(RECORDS) { record.copyInto(body, it * record.size) }

        frame = ByteArray(HEADER + body.size)
        writeInt(frame, 0, 1) // correlationId
        frame[4] = 0
        frame[5] = 0 // errorCode NONE
        writeLong(frame, 6, RECORDS.toLong())
        writeInt(frame, 14, body.size)
        body.copyInto(frame, HEADER)
    }

    @Benchmark
    @OperationsPerInvocation(RECORDS)
    fun reader(): Int = ResponseReader.fetch(frame).records.size

    @Benchmark
    @OperationsPerInvocation(RECORDS)
    fun decoder(): Int = ResponseDecoder.fetch(frame, Offset.ZERO).records.size

    private fun writeInt(
        target: ByteArray,
        at: Int,
        value: Int,
    ) {
        repeat(4) { target[at + it] = (value ushr (24 - 8 * it)).toByte() }
    }

    private fun writeLong(
        target: ByteArray,
        at: Int,
        value: Long,
    ) {
        repeat(8) { target[at + it] = (value ushr (56 - 8 * it)).toByte() }
    }

    private companion object {
        /** correlationId, errorCode, highWatermark, payloadBytes. */
        const val HEADER = 4 + 2 + 8 + 4
        const val RECORD_HEADER = 4 + 4

        /** Records per response, fixed so that the parameter varies one thing only. */
        const val RECORDS = 64
    }
}
