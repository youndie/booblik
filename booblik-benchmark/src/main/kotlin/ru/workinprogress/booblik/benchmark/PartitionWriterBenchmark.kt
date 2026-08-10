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
 * M-14: what does an acknowledgement per record actually cost, and how much of it does batching
 * give back?
 *
 * The original design draft made the unit a single record: a `CompletableDeferred` and a channel
 * round trip each time. This measures that shape (`batchSize = 1`) against the one that shipped,
 * on both write paths, so the answer is a ratio rather than an opinion.
 *
 * Every invocation writes the same [RECORDS_PER_OP] records regardless of the batch size, and
 * `@OperationsPerInvocation` divides by it — so the score is **records per second** in every row,
 * directly comparable with `SegmentAppendBenchmark`, which measures the same records going into
 * the same segment with no actor in front of them. The gap between the two is the actor's price.
 *
 * `AckPolicy.FORCED` is not among the parameters. A barrier costs milliseconds and would drown
 * everything this benchmark is trying to separate; it gets its own measurement in
 * [GroupCommitBenchmark], where the barrier is the subject rather than the noise.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class PartitionWriterBenchmark {
    @Param("1", "10", "100")
    var batchSize: Int = 1

    @Param("FILE_CHANNEL", "MAPPED")
    var mode: String = "FILE_CHANNEL"

    @Param("WRITTEN", "NONE")
    var ackPolicy: String = "WRITTEN"

    private lateinit var dir: Path
    private lateinit var log: PartitionLog
    private lateinit var writer: PartitionWriter
    private lateinit var scope: CoroutineScope
    private lateinit var batch: List<ByteArray>
    private lateinit var policy: AckPolicy
    private var batchesPerOp: Int = 0

    @Setup
    fun setUp() {
        RuntimeFootprint.verify()
        require(RECORDS_PER_OP % batchSize == 0) { "batch size must divide $RECORDS_PER_OP" }
        batchesPerOp = RECORDS_PER_OP / batchSize
        policy = AckPolicy.valueOf(ackPolicy)
        batch = List(batchSize) { ByteArray(PAYLOAD_SIZE) { i -> i.toByte() } }

        dir = MeasurementDir.create("booblik-writer-bench")
        // A real PartitionLog, not a single segment: rolling is part of what the writer does, and
        // at these volumes it happens for real. Retention keeps the directory from growing without
        // bound — see the trim in the benchmark body.
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
    @OperationsPerInvocation(RECORDS_PER_OP)
    fun append() =
        runBlocking {
            repeat(batchesPerOp) {
                writer.append(batch, policy)
            }
            // Retention runs inside the measured method on purpose. A broker keeps a bounded log,
            // so paying for it is part of the number; hoisting it into a setup step would measure
            // a broker that never deletes anything and then wonder why production is slower.
            log.retainAtMost(RETAINED_BYTES)
        }

    private companion object {
        /**
         * Constant, so `@OperationsPerInvocation` can divide by it and every row reports records
         * per second no matter how they were grouped.
         */
        const val RECORDS_PER_OP = 1000

        const val PAYLOAD_SIZE = 64
        const val SEGMENT_CAPACITY = 64 * 1024 * 1024
        const val RETAINED_BYTES = 256L * 1024 * 1024
    }
}
