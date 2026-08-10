package ru.workinprogress.booblik.benchmark.probe

import org.HdrHistogram.Histogram
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.client.BooblikClient
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The open-loop load generator, shared by the in-process probe ([LoadProbe]) and the two-machine
 * one ([RemoteLoadProbe]).
 *
 * It lives apart from both because M-38 needs the *same* generator on both sides of the comparison.
 * If the distributed run had its own copy of this loop, every difference it found would have two
 * candidate explanations — the network, and the second implementation — and the second one is
 * unfalsifiable without reading both carefully. One driver, two callers.
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
 * would be about the round trip rather than about the broker.
 */
internal object LoadDriver {
    val TOPIC = TopicName("bench")
    val PARTITION = PartitionId(0)

    const val RECORD_SIZE = 128
    const val BATCH_SIZE = 10
    const val FETCH_MAX_BYTES = 64 * 1024
    const val PRELOAD_RECORDS = 200_000
    const val MAX_IN_FLIGHT = 1024

    enum class Workload { PRODUCE, FETCH }

    /**
     * What one run produced.
     *
     * [bytes] is counted because M-38 is a question about **bandwidth**, not about requests: zero-copy
     * saves a copy per byte, so the axis it can move is bytes per second, and a request count says
     * nothing about it without the record size beside it.
     */
    data class Result(
        val completed: Long,
        val bytes: Long,
        val histogram: Histogram,
    )

    /** Fills the log through the ordinary socket path, so nothing about the read path is faked. */
    fun preload(
        address: InetSocketAddress,
        records: Int = PRELOAD_RECORDS,
        recordSize: Int = RECORD_SIZE,
    ) {
        BooblikClient(address).use { client ->
            var written = 0
            while (written < records) {
                val batch = List(100) { ByteArray(recordSize) { i -> i.toByte() } }
                client.sendProduce(TOPIC, PARTITION, batch)
                client.receiveProduce()
                written += batch.size
            }
        }
        println("# preloaded $records records of $recordSize B")
    }

    fun drive(
        workload: Workload,
        address: InetSocketAddress,
        connections: Int,
        targetRate: Int,
        seconds: Long,
        pipelineDepth: Int,
        fetchMaxBytes: Int = FETCH_MAX_BYTES,
        preloadedRecords: Int = PRELOAD_RECORDS,
    ): Result {
        val perConnectionRate = targetRate.toDouble() / connections
        val intervalNanos = (1_000_000_000.0 / perConnectionRate).toLong()
        val ready = CountDownLatch(connections)
        val start = CountDownLatch(1)
        val done = CountDownLatch(connections)
        val histograms = Array(connections) { Histogram(TimeUnit.MINUTES.toNanos(1), 3) }
        val total = Histogram(TimeUnit.MINUTES.toNanos(1), 3)
        var completed = 0L
        var bytes = 0L

        val threads =
            (0 until connections).map { index ->
                Thread({
                    BooblikClient(address).use { client ->
                        // Requests due but not yet answered. Bounded on purpose: an unbounded queue
                        // would let the harness hide a broker that has stopped answering, by
                        // accumulating work in the client instead of reporting latency.
                        val inFlight = ArrayBlockingQueue<Long>(MAX_IN_FLIGHT)
                        // Bounds how many requests may be outstanding. Released by the receiver
                        // *after* the answer is recorded, not when it is dequeued — otherwise
                        // depth 1 would let the sender go one request early and stop being
                        // request-response at all.
                        val slots = java.util.concurrent.Semaphore(pipelineDepth)
                        val local = histograms[index]
                        var localBytes = 0L

                        val receiver =
                            Thread({
                                try {
                                    while (true) {
                                        val due = inFlight.take()
                                        if (due == POISON) break
                                        when (workload) {
                                            Workload.PRODUCE -> {
                                                client.receiveProduce()
                                                localBytes += BATCH_SIZE.toLong() * RECORD_SIZE
                                            }

                                            Workload.FETCH -> {
                                                val answer = client.receiveFetch()
                                                localBytes += answer.records.sumOf { it.size + RECORD_HEADER }
                                            }
                                        }
                                        local.recordValue(System.nanoTime() - due)
                                        slots.release()
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
                            slots.acquire()

                            when (workload) {
                                Workload.PRODUCE -> {
                                    client.sendProduce(TOPIC, PARTITION, batch, AckPolicy.WRITTEN)
                                }

                                Workload.FETCH -> {
                                    client.sendFetch(TOPIC, PARTITION, Offset(offset), fetchMaxBytes)
                                    offset = (offset + FETCH_STRIDE) % preloadedRecords
                                }
                            }
                            inFlight.put(due)
                            issued += 1
                        }
                        inFlight.put(POISON)
                        receiver.join(TimeUnit.SECONDS.toMillis(10))
                        // Anything the receiver never got to is not a completed request, and the
                        // semaphore would otherwise keep the next thread waiting on a dead one.
                        slots.release(pipelineDepth)
                        synchronized(total) {
                            completed += local.totalCount
                            bytes += localBytes
                            total.add(local)
                        }
                    }
                    done.countDown()
                }, "load-tx-$index").apply { isDaemon = true }
            }

        threads.forEach(Thread::start)
        ready.await()
        start.countDown()
        done.await()
        return Result(completed, bytes, total)
    }

    fun reportLatency(histogram: Histogram) {
        println("#")
        println("#   latency from the moment each request was DUE (not sent):")
        for (percentile in listOf(50.0, 90.0, 99.0, 99.9, 99.99)) {
            println("#     p%-6s %10.3f ms".format(percentile, histogram.getValueAtPercentile(percentile) / 1e6))
        }
        println("#     max     %10.3f ms".format(histogram.maxValue / 1e6))
    }

    fun gcSnapshot(): Pair<Long, Long> {
        val beans =
            java.lang.management.ManagementFactory
                .getGarbageCollectorMXBeans()
        return beans.sumOf { it.collectionCount } to beans.sumOf { it.collectionTime }
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

    /** `[int32 size][int32 crc32c]` in front of every record, on the wire exactly as on disk. */
    private const val RECORD_HEADER = 8
    private const val POISON = Long.MIN_VALUE
    private const val FETCH_STRIDE = 400L

    /** Last 50 µs before a request is due are spun; everything before that is parked. */
    private const val SPIN_TAIL_NANOS = 50_000L
}
