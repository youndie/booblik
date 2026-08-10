package ru.workinprogress.booblik.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.OperationsPerInvocation
import java.util.concurrent.TimeUnit

/**
 * M-76: what the `Flow` in a subscription costs, with the network taken out of the question.
 *
 * The probe could not answer this and said so: over a socket the pass-to-pass spread is twenty per
 * cent and more, which buries anything the abstraction could plausibly cost. That is a useful
 * answer about **practice** — at the rates a broker runs at, the wrapper is not what limits you —
 * but it is not an answer about the wrapper.
 *
 * So the socket is removed and only the shape is left: the same batches, handed over three ways.
 *
 * * [loop] — handle each batch inline;
 * * [coldFlow] — emit and collect, no channel, no thread hop. Against [loop] this is the `Flow`
 *   machinery and nothing else;
 * * [bufferedFlow] — a channel between producing and collecting, which is what `callbackFlow`
 *   gives the real subscription. Against [coldFlow] this is the decoupling, priced separately
 *   rather than hidden inside "the cost of Flow" — mistaking one for the other is exactly what
 *   made the first version of this comparison report a wrapper as faster than the thing it wraps.
 *
 * [batchSize] is a parameter because the answer depends on it entirely: the cost is per emission,
 * so it is divided by however many records an emission carries. A subscription that delivers one
 * record at a time would pay it in full — which is the arithmetic reason [RecordBatch] delivers
 * batches (M-71).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
class FlowOverheadBenchmark {
    @Param("1", "10", "100")
    var batchSize: Int = 10

    private lateinit var batches: List<List<ByteArray>>

    @Setup
    fun setUp() {
        val record = ByteArray(RECORD_SIZE)
        val batch = List(batchSize) { record }
        batches = List(BATCHES) { batch }
    }

    @Benchmark
    @OperationsPerInvocation(BATCHES)
    fun loop(): Int {
        var seen = 0
        for (batch in batches) seen += batch.size
        return seen
    }

    @Benchmark
    @OperationsPerInvocation(BATCHES)
    fun coldFlow(): Int =
        runBlocking {
            var seen = 0
            flow { batches.forEach { emit(it) } }.collect { seen += it.size }
            seen
        }

    @Benchmark
    @OperationsPerInvocation(BATCHES)
    fun bufferedFlow(): Int =
        runBlocking {
            var seen = 0
            flow { batches.forEach { emit(it) } }.buffer().collect { seen += it.size }
            seen
        }

    private companion object {
        /** Enough emissions that the per-invocation overhead of the harness does not dominate. */
        const val BATCHES = 10_000
        const val RECORD_SIZE = 128
    }
}
