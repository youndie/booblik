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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.OperationsPerInvocation
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * What group commit is worth: durable writes per second as the number of producers waiting on the
 * same disk barrier goes up.
 *
 * M0 measured a barrier at roughly 4 ms, which caps a single producer near 250 durable batches per
 * second. The cap belongs to the *barrier*, though, not to the producer — so if the writer collects
 * everyone who is already queued and forces once for all of them, throughput should climb roughly
 * with the number of concurrent producers while each one still waits about 4 ms.
 *
 * That is the claim. This measures whether it holds, and where it stops holding.
 *
 * One invocation = [producers] concurrent durable single-record appends, and
 * `@OperationsPerInvocation` is not used because the count is a parameter: the score is
 * **invocations per second**, so records per second is score × producers. Stated here rather than
 * left to the reader, because that multiplication is exactly the mistake this benchmark invites.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class GroupCommitBenchmark {
    @Param("1", "8", "64")
    var producers: Int = 1

    @Param("FILE_CHANNEL", "MAPPED")
    var mode: String = "FILE_CHANNEL"

    private lateinit var dir: Path
    private lateinit var log: PartitionLog
    private lateinit var writer: PartitionWriter
    private lateinit var scope: CoroutineScope
    private lateinit var record: ByteArray

    @Setup
    fun setUp() {
        RuntimeFootprint.verify()
        record = ByteArray(PAYLOAD_SIZE) { it.toByte() }
        dir = Files.createTempDirectory("booblik-groupcommit")
        log = PartitionLog.open(dir, SegmentMode.valueOf(mode), segmentCapacity = SEGMENT_CAPACITY)
        scope = CoroutineScope(SupervisorJob())
        writer = PartitionWriter(log, scope)
    }

    @TearDown
    fun tearDown() {
        runBlocking { writer.close() }
        scope.cancel()
        log.close()
        @OptIn(ExperimentalPathApi::class)
        dir.deleteRecursively()
    }

    @Benchmark
    @OperationsPerInvocation(1)
    fun durableAppend() =
        runBlocking {
            (0 until producers)
                .map { async { writer.append(record, AckPolicy.FORCED) } }
                .awaitAll()
        }

    private companion object {
        const val PAYLOAD_SIZE = 64
        const val SEGMENT_CAPACITY = 64 * 1024 * 1024
    }
}
