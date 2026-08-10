package ru.workinprogress.booblik.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.storage.LogSegment
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * The benchmark that decides research Р1: does writing through a memory mapping actually beat a
 * plain `FileChannel` append, as the draft assumes?
 *
 * The number reported is **records per second at the storage layer** — an upper bound on the
 * broker's RPS, since nothing downstream can be faster than the log it writes to.
 *
 * Three things about the methodology, each of which changes the answer by more than the difference
 * being measured:
 *
 * * **`flush` is a parameter, not a setting.** Without a flush both paths measure the speed of the
 *   page cache; with one they measure the disk. Both are legitimate answers to different questions,
 *   and a number without this parameter attached is not an answer to either.
 * * **The segment is recreated per iteration, not per invocation.** Creating it per invocation
 *   would measure `open` and, for the mapped path, the pre-sizing of a half-gigabyte file.
 * * **Temp directory, and it is a real filesystem.** On macOS `/tmp` is APFS on SSD; in CI it is
 *   whatever the runner mounts. Compare numbers across runs only on the same host — see
 *   docs/benchmarking.md.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class SegmentAppendBenchmark {
    @Param("FILE_CHANNEL", "MAPPED")
    var mode: String = "FILE_CHANNEL"

    @Param("64", "1024")
    var payloadSize: Int = 64

    @Param("false", "true")
    var flushEveryAppend: Boolean = false

    private lateinit var dir: Path
    private lateinit var segment: LogSegment
    private lateinit var payload: ByteArray

    @Setup
    fun setUp() {
        dir = Files.createTempDirectory("booblik-bench")
        payload = ByteArray(payloadSize) { it.toByte() }
        openSegment()
    }

    @TearDown
    fun tearDown() {
        segment.close()
        @OptIn(ExperimentalPathApi::class)
        dir.deleteRecursively()
    }

    /**
     * Returns the raw `Long`, not the [Offset] it really is, and that is not sloppiness: a Kotlin
     * function returning a value class gets a **mangled** JVM name (`append-SgWxkiU`), and JMH's
     * generator refuses it with "Benchmark function name is not a valid Java identifier". The
     * return value itself has to stay — JMH uses it as the blackhole that keeps the append from
     * being optimised away.
     */
    @Benchmark
    fun append(): Long {
        // A full segment is not an error here, it is the end of the runway. The rollover cost lands
        // in the number as a rare outlier; at a gigabyte per iteration it happens at most a few
        // times per run, and throughput mode averages over millions of invocations. If it ever
        // shows up in the variance, the fix is a shorter iteration, not a bigger segment — the
        // mapped path pre-sizes the file, and Int.MAX_VALUE is the ceiling for both paths.
        if (!segment.hasRoomFor(payload.size)) {
            segment.close()
            openSegment()
        }
        val offset = segment.append(payload)
        if (flushEveryAppend) segment.force()
        return offset.value
    }

    private fun openSegment() {
        segment = LogSegment.open(dir, Offset.ZERO, SegmentMode.valueOf(mode), CAPACITY)
    }

    private companion object {
        /** 1 GiB — a couple of seconds of runway even at the fastest parameter combination. */
        const val CAPACITY = 1024 * 1024 * 1024
    }
}
