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
 * * **A full segment is recycled, not reopened.** Reopening would fold an unmap and a remap of a
 *   gigabyte into the measured method — which is what M-27 was about. How often it happened is
 *   printed at teardown, so a suspicious row can be checked rather than guessed at.
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
    private var recycles: Int = 0

    @Setup
    fun setUp() {
        RuntimeFootprint.verify()
        dir = MeasurementDir.create("booblik-bench")
        payload = ByteArray(payloadSize) { it.toByte() }
        recycles = 0
        openSegment()
    }

    @TearDown
    fun tearDown() {
        // Reported rather than assumed. If recycling ever stops being cheap, this line is what
        // says whether a suspicious row had one of them in it or a thousand.
        println(
            "# recycled the segment $recycles times (mode=$mode, payloadSize=$payloadSize, flush=$flushEveryAppend)",
        )
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
        // A full segment is recycled in place — the write position goes back to zero — rather than
        // closed and reopened. That is M-27, and it was not a micro-optimisation: at a kilobyte per
        // record the mapped path fills a gigabyte roughly every quarter second, so a two-second
        // iteration used to contain up to ten unmap/remap cycles of a gigabyte each. The row was
        // measuring rollover, not `append`, and it showed: 28 % error against 3-8 % everywhere
        // else. Recycling is a couple of field stores, so what is left in the number is the append.
        //
        // Making the segment bigger instead was never available: `Int.MAX_VALUE` is the ceiling for
        // both write paths, only twice what is already used here.
        if (!segment.hasRoomFor(payload.size)) {
            segment.truncateTo(Offset.ZERO)
            recycles += 1
        }
        val offset = segment.append(payload)
        if (flushEveryAppend) segment.force()
        return offset.value
    }

    private fun openSegment() {
        segment = LogSegment.open(dir, Offset.ZERO, SegmentMode.valueOf(mode), CAPACITY)
    }

    private companion object {
        /**
         * 1 GiB. Not chosen to avoid recycling — recycling is cheap now — but to keep the volume of
         * dirty pages in play realistic. Shrink it and the kernel would be writing back the same few
         * pages, which is a benchmark of the page cache and not of a log.
         */
        const val CAPACITY = 1024 * 1024 * 1024
    }
}
