package ru.workinprogress.booblik.benchmark.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.HdrHistogram.Histogram
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.BooblikServer
import ru.workinprogress.booblik.net.FetchMode
import ru.workinprogress.booblik.net.PartitionHandle
import ru.workinprogress.booblik.net.PartitionRegistry
import ru.workinprogress.booblik.net.ServerConfig
import ru.workinprogress.booblik.net.Transport
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * M-33/M-34: end-to-end throughput and latency against a real broker over a real socket.
 *
 * ## Open loop, and why that is the whole design
 *
 * Each connection issues requests on a **fixed schedule** — request `i` is due at
 * `start + i × interval` — and latency is measured from when it was *due*, not from when it was
 * sent. A closed-loop harness ("send, wait for the answer, send the next") cannot do this: when the
 * broker slows down, a closed loop slows down with it and simply stops asking for as much. The
 * queue that would have formed in front of a real broker never forms, the samples that would have
 * been slow are never taken, and the percentiles come out beautiful. That is coordinated omission,
 * and it is the default failure of hand-written load tests.
 *
 * Measuring from the due time is what makes a backlog visible: if the broker stalls for 100 ms,
 * every request that came due during the stall carries that delay in its own sample.
 *
 * ## Pipelining
 *
 * Sending and receiving are separate threads per connection, so a connection can have many requests
 * in flight. Without that, throughput would be bounded by the round-trip time and the measurement
 * would be about the loopback interface.
 *
 * Usage: `-Pargs="PRODUCE SELECTOR ZERO_COPY <connections> <ratePerSecond> <seconds>"`.
 */
object LoadProbe {
    private val TOPIC = TopicName("bench")
    private val PARTITION = PartitionId(0)

    private const val RECORD_SIZE = 128
    private const val BATCH_SIZE = 10
    private const val FETCH_MAX_BYTES = 64 * 1024
    private const val SEGMENT_CAPACITY = 128 * 1024 * 1024
    private const val PRELOAD_RECORDS = 200_000

    private enum class Workload { PRODUCE, FETCH }

    @JvmStatic
    fun main(args: Array<String>) {
        val workload = Workload.valueOf(args.getOrElse(0) { "PRODUCE" })
        val transport = Transport.valueOf(args.getOrElse(1) { "SELECTOR" })
        val fetchMode = FetchMode.valueOf(args.getOrElse(2) { "ZERO_COPY" })
        val connections = args.getOrElse(3) { "8" }.toInt()
        val targetRate = args.getOrElse(4) { "20000" }.toInt()
        val seconds = args.getOrElse(5) { "20" }.toLong()
        // A knob because it turned out to matter: a segment that fills during the run puts a roll
        // inside the measurement, and a roll is visible in the tail.
        val segmentCapacity = args.getOrElse(6) { SEGMENT_CAPACITY.toString() }.toInt()

        val dir = Files.createTempDirectory("booblik-load")
        val scope = CoroutineScope(SupervisorJob())
        val log = PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, segmentCapacity)
        val writer = PartitionWriter(log, scope)
        val server =
            BooblikServer(
                PartitionRegistry.of(PartitionRegistry.Key(TOPIC, PARTITION) to PartitionHandle(log, writer)),
                ServerConfig(port = 0, transport = transport, fetchMode = fetchMode),
            )

        try {
            val address = server.start()
            println("# M-33/M-34 load probe: $workload, $transport, fetch=$fetchMode")
            println("# $connections connections, target $targetRate/s total, ${seconds}s")
            println(
                "# records of $RECORD_SIZE B, batches of $BATCH_SIZE; segment capacity ${segmentCapacity / 1024 / 1024} MiB",
            )

            if (workload == Workload.FETCH) preload(address)

            val histogram = Histogram(TimeUnit.MINUTES.toNanos(1), 3)
            val gcBefore = gcSnapshot()
            val completed = run(workload, address, connections, targetRate, seconds, histogram)
            val gcAfter = gcSnapshot()

            report(workload, targetRate, seconds, completed, histogram, log.nextOffset)
            reportGc(gcBefore, gcAfter, seconds)
        } finally {
            server.close()
            scope.cancel()
            log.close()
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** FETCH needs something to fetch. Written through the same socket path, so nothing is faked. */
    private fun preload(address: InetSocketAddress) {
        BooblikClient(address).use { client ->
            var written = 0
            while (written < PRELOAD_RECORDS) {
                val batch = List(100) { ByteArray(RECORD_SIZE) { i -> i.toByte() } }
                client.sendProduce(TOPIC, PARTITION, batch)
                client.receiveProduce()
                written += batch.size
            }
        }
        println("# preloaded $PRELOAD_RECORDS records")
    }

    private fun run(
        workload: Workload,
        address: InetSocketAddress,
        connections: Int,
        targetRate: Int,
        seconds: Long,
        histogram: Histogram,
    ): Long {
        val perConnectionRate = targetRate.toDouble() / connections
        val intervalNanos = (1_000_000_000.0 / perConnectionRate).toLong()
        val ready = CountDownLatch(connections)
        val start = CountDownLatch(1)
        val done = CountDownLatch(connections)
        val histograms = Array(connections) { Histogram(TimeUnit.MINUTES.toNanos(1), 3) }
        var completed = 0L

        val threads =
            (0 until connections).map { index ->
                Thread({
                    BooblikClient(address).use { client ->
                        // Requests due but not yet answered. Bounded on purpose: an unbounded queue
                        // would let the harness hide a broker that has stopped answering, by
                        // accumulating work in the client instead of reporting latency.
                        val inFlight = ArrayBlockingQueue<Long>(MAX_IN_FLIGHT)
                        val local = histograms[index]

                        val receiver =
                            Thread({
                                try {
                                    while (true) {
                                        val due = inFlight.take()
                                        if (due == POISON) break
                                        when (workload) {
                                            Workload.PRODUCE -> client.receiveProduce()
                                            Workload.FETCH -> client.receiveFetch()
                                        }
                                        local.recordValue(System.nanoTime() - due)
                                    }
                                } catch (_: Exception) {
                                    // The connection went away; the run is over for this thread.
                                }
                            }, "load-rx-$index")
                        receiver.isDaemon = true

                        ready.countDown()
                        start.await()
                        receiver.start()

                        // Allocated once, sent every time. A harness that allocates its payload per
                        // request is a harness that measures its own garbage: at fifteen thousand
                        // batches a second this was nineteen megabytes per second of short-lived
                        // arrays, in the same 64 MiB heap as the broker.
                        val batch = List(BATCH_SIZE) { ByteArray(RECORD_SIZE) { b -> b.toByte() } }

                        val began = System.nanoTime()
                        val until = began + seconds * 1_000_000_000L
                        var issued = 0L
                        var offset = 0L
                        while (System.nanoTime() < until) {
                            // The due time comes from the schedule, not from the clock. This is the
                            // line that makes the measurement open-loop.
                            val due = began + issued * intervalNanos
                            val wait = due - System.nanoTime()
                            if (wait > 0) parkNanos(wait)

                            when (workload) {
                                Workload.PRODUCE -> {
                                    client.sendProduce(TOPIC, PARTITION, batch, AckPolicy.WRITTEN)
                                }

                                Workload.FETCH -> {
                                    client.sendFetch(TOPIC, PARTITION, Offset(offset), FETCH_MAX_BYTES)
                                    offset = (offset + FETCH_STRIDE) % PRELOAD_RECORDS
                                }
                            }
                            inFlight.put(due)
                            issued += 1
                        }
                        inFlight.put(POISON)
                        receiver.join(TimeUnit.SECONDS.toMillis(10))
                        synchronized(histogram) {
                            completed += local.totalCount
                            histogram.add(local)
                        }
                    }
                    done.countDown()
                }, "load-tx-$index").apply { isDaemon = true }
            }

        threads.forEach(Thread::start)
        ready.await()
        start.countDown()
        done.await()
        return completed
    }

    private fun report(
        workload: Workload,
        targetRate: Int,
        seconds: Long,
        completed: Long,
        histogram: Histogram,
        logEnd: Offset,
    ) {
        val achieved = completed.toDouble() / seconds
        println()
        println("# result")
        println("#   target        %,10.0f requests/s".format(targetRate.toDouble()))
        println(
            "#   achieved      %,10.0f requests/s  (%.0f%% of target)".format(achieved, 100 * achieved / targetRate),
        )
        if (workload == Workload.PRODUCE) {
            println("#   records       %,10.0f records/s".format(achieved * BATCH_SIZE))
        }
        println("#   completed     %,10d requests, log end offset $logEnd".format(completed))
        println("#")
        println("#   latency from the moment each request was DUE (not sent):")
        for (percentile in listOf(50.0, 90.0, 99.0, 99.9, 99.99)) {
            println("#     p%-6s %10.3f ms".format(percentile, histogram.getValueAtPercentile(percentile) / 1e6))
        }
        println("#     max     %10.3f ms".format(histogram.maxValue / 1e6))
    }

    /**
     * Collections and pause time, because a latency tail with no explanation is an invitation to
     * invent one.
     *
     * The broker and the load generator share this JVM and its 64 MiB heap, so a pause here stops
     * both — the requests that came due during it are delayed, and the harness that should have
     * been issuing them is also stopped. That is a real contaminant of these numbers and the reason
     * it gets printed next to them rather than mentioned in a footnote. Splitting the two into
     * separate processes is M-37.
     */
    private fun gcSnapshot(): Pair<Long, Long> {
        val beans =
            java.lang.management.ManagementFactory
                .getGarbageCollectorMXBeans()
        return beans.sumOf { it.collectionCount } to beans.sumOf { it.collectionTime }
    }

    private fun reportGc(
        before: Pair<Long, Long>,
        after: Pair<Long, Long>,
        seconds: Long,
    ) {
        val collections = after.first - before.first
        val millis = after.second - before.second
        println("#")
        println(
            "#   GC during the run: %d collections, %d ms total (%.1f%% of wall clock)".format(
                collections,
                millis,
                100.0 * millis / (seconds * 1000),
            ),
        )
        println("#   note: the load generator shares this JVM and its heap with the broker (M-37)")
    }

    /**
     * Waits until a request is due: parked for the bulk of it, spinning only for the last stretch.
     *
     * The split is the whole point, and getting it wrong cost a full round of bogus numbers. The
     * first version spun for anything under two milliseconds — which, at eight connections and ten
     * thousand requests a second, is *every* wait, so eight sender threads sat burning eight cores
     * on an eight-core machine. The broker then had to be scheduled against its own load generator,
     * and the result was a p99 of 110 ms that had nothing to do with the broker.
     *
     * Parking alone is not the answer either: the OS will happily oversleep a sub-millisecond park,
     * and a sender that oversleeps stops applying the offered rate. So park until the last
     * [SPIN_TAIL_NANOS], then spin — one core-microsecond per request instead of a whole core.
     */
    private fun parkNanos(nanos: Long) {
        if (nanos > SPIN_TAIL_NANOS) {
            java.util.concurrent.locks.LockSupport
                .parkNanos(nanos - SPIN_TAIL_NANOS)
        }
        val until = System.nanoTime() + minOf(nanos, SPIN_TAIL_NANOS)
        while (System.nanoTime() < until) Thread.onSpinWait()
    }

    private const val MAX_IN_FLIGHT = 1024
    private const val POISON = Long.MIN_VALUE
    private const val FETCH_STRIDE = 400L

    /** Last 50 µs before a request is due are spun; everything before that is parked. */
    private const val SPIN_TAIL_NANOS = 50_000L
}
