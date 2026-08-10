package ru.workinprogress.booblik.benchmark.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.benchmark.MeasurementDir
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.BooblikServer
import ru.workinprogress.booblik.net.PartitionHandle
import ru.workinprogress.booblik.net.PartitionRegistry
import ru.workinprogress.booblik.net.ServerConfig
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.client.BooblikConnection
import ru.workinprogress.booblik.net.client.BooblikSubscriber
import ru.workinprogress.booblik.net.client.Consumer
import ru.workinprogress.booblik.net.client.OffsetStore
import ru.workinprogress.booblik.net.client.StartPosition
import ru.workinprogress.booblik.net.client.SubscriptionConfig
import ru.workinprogress.booblik.net.client.checkpointing
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.net.InetSocketAddress
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * M-74: what the subscription costs, and what long FETCH bought.
 *
 * Two questions, and the first is the one M-75 was built to answer. A caught-up consumer has
 * nothing to read; the only question is how often it asks. Polling makes that a function of the
 * interval, long FETCH makes it a function of the wait, and the ratio between them is the entire
 * justification for the milestone — which until now was an argument rather than a number.
 *
 * The second question is what the `Flow` costs against a bare `poll()` loop over the same records.
 *
 * Both are **relative** comparisons inside one run: the harness shares this JVM with the broker
 * (M-37), so the absolute numbers describe a pair rather than a broker. That contaminates both
 * sides equally, which is what makes the ratio usable and the absolutes not.
 */
object SubscriptionProbe {
    private val TOPIC = TopicName("bench")
    private const val PARTITIONS = 4
    private const val RECORD_SIZE = 128
    private const val MAX_BYTES = 1 shl 20

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrElse(0) { "IDLE" }.uppercase()
        val consumers = args.getOrElse(1) { "8" }.toInt()
        val seconds = args.getOrElse(2) { "10" }.toLong()
        val records = args.getOrElse(3) { "200000" }.toInt()
        val pollIntervalMillis = args.getOrElse(4) { "50" }.toLong()
        // Comparable to the window on purpose. At the 30 s default no request would renew inside a
        // ten-second measurement, and "zero requests" is true but says nothing about the rate — the
        // ratio it produces is an artefact of the window, not a property of the strategy.
        val maxWaitMillis = args.getOrElse(5) { "2000" }.toInt()
        val passes = args.getOrElse(6) { "3" }.toInt()
        // `host:port` runs against a broker that is already up somewhere else, which is the whole
        // point of M-37: with the broker in this JVM the harness shares its heap, its scheduler and
        // its GC, and at millions of records a second that scheduling *is* what gets measured
        // (замер 15.2 could not resolve anything because of it).
        val remote = args.getOrElse(7) { "" }

        if (remote.isNotEmpty()) {
            val (host, port) = remote.split(":")
            val address = InetSocketAddress(host, port.toInt())
            println("# M-74/M-37 subscription probe: $mode against $remote")
            println("# host JVM: ${System.getProperty("java.vm.version")}, ${System.getProperty("os.name")}")
            println("# CLIENT ONLY — the broker is another process on another machine")
            runBlocking {
                when (mode) {
                    "IDLE" -> error("IDLE needs the broker's own counters; run it in-process")
                    "THROUGHPUT" -> remoteThroughput(address, records, passes)
                    else -> error("mode must be IDLE or THROUGHPUT, got $mode")
                }
            }
            return
        }

        val dir = MeasurementDir.create("booblik-subscription")
        val scope = CoroutineScope(SupervisorJob())
        val handles =
            (0 until PARTITIONS).associate { id ->
                val log = PartitionLog.open(dir.resolve("bench-$id"), SegmentMode.MAPPED)
                PartitionRegistry.Key(TOPIC, PartitionId(id)) to PartitionHandle(log, PartitionWriter(log, scope))
            }
        val server =
            BooblikServer(
                PartitionRegistry(handles),
                ServerConfig(port = 0, bindAddress = "127.0.0.1"),
            )

        try {
            val address = server.start()
            println("# M-74 subscription probe: $mode")
            println("# host JVM: ${System.getProperty("java.vm.version")}, ${System.getProperty("os.name")}")
            println("# storage: ${MeasurementDir.describe(dir)}")
            println("# the harness shares this JVM with the broker (M-37): ratios are usable, absolutes are not")

            when (mode) {
                "IDLE" -> idle(server, address, consumers, seconds, pollIntervalMillis, maxWaitMillis)
                "THROUGHPUT" -> throughput(server, address, records, passes)
                else -> error("mode must be IDLE or THROUGHPUT, got $mode")
            }
        } finally {
            server.close()
            scope.cancel()
            runBlocking { handles.values.forEach { it.log.close() } }
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /**
     * How much a consumer that has nothing to read costs the broker.
     *
     * Both strategies read the same empty topic and deliver the same nothing. What differs is the
     * number of requests that crossed the wire to establish it.
     */
    private fun idle(
        server: BooblikServer,
        address: InetSocketAddress,
        consumers: Int,
        seconds: Long,
        pollIntervalMillis: Long,
        maxWaitMillis: Int,
    ) = runBlocking {
        println(
            "# $consumers caught-up consumers, ${seconds}s, " +
                "poll interval ${pollIntervalMillis}ms, held wait ${maxWaitMillis}ms",
        )
        println()

        val polling =
            measure(server, seconds) { scope ->
                repeat(consumers) { index ->
                    scope.launch(Dispatchers.IO) {
                        BooblikConnection(address, scope).use { connection ->
                            val consumer = Consumer(connection, TOPIC, PartitionId(index % PARTITIONS))
                            while (true) {
                                consumer.poll()
                                delay(pollIntervalMillis)
                            }
                        }
                    }
                }
            }

        val holding =
            measure(server, seconds) { scope ->
                repeat(consumers) { index ->
                    scope.launch(Dispatchers.IO) {
                        val config = SubscriptionConfig(maxWaitMillis = maxWaitMillis)
                        BooblikSubscriber(address, config).use { subscriber ->
                            subscriber
                                .follow(TOPIC, StartPosition.Latest, listOf(PartitionId(index % PARTITIONS)))
                                .collect { }
                        }
                    }
                }
            }

        report("polling every ${pollIntervalMillis}ms", polling, consumers, seconds, 1000.0 / pollIntervalMillis)
        report("long FETCH, ${maxWaitMillis}ms wait", holding, consumers, seconds, 1000.0 / maxWaitMillis)
        println()
        if (holding == 0L) {
            println("# no held request renewed inside the window — the ratio is undefined, not infinite.")
            println("# Re-run with a wait shorter than the window to measure it.")
        } else {
            println("# measured ratio: %.0f× fewer requests".format(polling.toDouble() / holding))
        }
        println("# The predicted column is 1/interval against 1/wait — printed so the measurement")
        println("# can be checked against the arithmetic rather than trusted.")
        println("# A caught-up consumer is not free either way: it is one held connection per")
        println("# partition. It stops being a source of *traffic*, which is a different resource.")
    }

    /**
     * The same three reads, against a broker this process did not start.
     *
     * No broker counters here on purpose: they belong to another machine, and a number this side
     * invented about the other would be worth nothing (the same rule `RemoteLoadProbe` follows).
     * Preloading happens over the wire like everything else.
     */
    private suspend fun remoteThroughput(
        address: InetSocketAddress,
        records: Int,
        passes: Int,
    ) {
        preload(address, records)
        println("# reading $records records of $RECORD_SIZE B back three ways, $passes passes")
        println()
        repeat(passes) { pass ->
            println("# pass ${pass + 1} of $passes")
            readThreeWays(address, records)
        }
    }

    /** Fetch requests the broker answered while [body] ran. */
    private suspend fun measure(
        server: BooblikServer,
        seconds: Long,
        body: (CoroutineScope) -> Unit,
    ): Long {
        val scope = CoroutineScope(SupervisorJob())
        return try {
            body(scope)
            delay(500) // let the consumers connect before the window opens
            val before = server.metrics.snapshot(null).fetchRequests
            delay(seconds * 1000)
            server.metrics.snapshot(null).fetchRequests - before
        } finally {
            scope.cancel()
            delay(200)
        }
    }

    private fun report(
        label: String,
        requests: Long,
        consumers: Int,
        seconds: Long,
        predictedPerConsumer: Double,
    ) {
        println(
            "#   %-26s %6d requests = %7.2f/s = %6.2f per consumer/s (predicted %.2f)".format(
                label,
                requests,
                requests.toDouble() / seconds,
                requests.toDouble() / seconds / consumers,
                predictedPerConsumer,
            ),
        )
    }

    /** What the `Flow` costs against a bare `poll()` loop over the same records. */
    private fun throughput(
        server: BooblikServer,
        address: InetSocketAddress,
        records: Int,
        passes: Int,
    ) = runBlocking {
        preload(address, records)
        println("# reading $records records of $RECORD_SIZE B back three ways, $passes passes")
        println()

        // Three passes, and every pass printed. The first run of this probe reported the `Flow` as
        // 55 % **faster** than the bare loop it wraps, which is not a thing an abstraction can be:
        // the three reads run in sequence in one JVM, so the first one pays for the JIT and the
        // last one inherits it. Printing the passes makes warmup visible instead of letting it
        // masquerade as a result.
        repeat(passes) { pass ->
            println("# pass ${pass + 1} of $passes")
            readThreeWays(address, records)
            println("#   fetch requests answered so far: ${server.metrics.snapshot(null).fetchRequests}")
        }
    }

    /**
     * Three reads that differ by **one thing each**, which is what M-76 had to fix.
     *
     * The previous version compared `Consumer.poll()` against the shipped subscription and reported
     * the subscription as up to 120 % *faster* than the loop it wraps. A wrapper cannot be faster
     * than what it wraps, so the comparison was wrong, and the reason turned out to be worth more
     * than the number: `callbackFlow` puts a channel between the fetch loop and the collector, so
     * the next request goes out while the previous batch is still being handled. The bare loop is
     * strictly sequential. That is real concurrency the subscription has and the loop does not —
     * not overhead, and not something a fair comparison should hide inside "the cost of Flow".
     *
     * So the ladder is explicit, and every rung shares the connection, the offsets and the fetch
     * loop with the one below it:
     *
     * 1. **bare loop** — fetch, handle, fetch;
     * 2. **cold `flow { }`** — the same loop, emitting instead of handling inline. No channel and
     *    no thread hop, so this rung and the one below differ *only* by the Flow machinery;
     * 3. **`buffer()`** — a channel between producing and collecting, which is what `callbackFlow`
     *    gives the real subscription. The step from 2 to 3 is the decoupling, priced on its own.
     *
     * Setup is outside the timed region on every rung: one connection, opened once, reused.
     */
    private suspend fun readThreeWays(
        address: InetSocketAddress,
        records: Int,
    ) = kotlinx.coroutines.coroutineScope {
        BooblikConnection(address, this).use { connection ->
            val bare =
                timed(records) {
                    var seen = 0
                    var position = Offset.ZERO
                    while (seen < records) {
                        val answer = connection.fetch(TOPIC, PartitionId(0), position, MAX_BYTES)
                        if (answer.records.isEmpty()) break
                        seen += answer.records.size
                        position += answer.records.size.toLong()
                    }
                    seen
                }

            val cold =
                timed(records) {
                    var seen = 0
                    fetchFlow(connection, records).collect { seen += it }
                    seen
                }

            val buffered =
                timed(records) {
                    var seen = 0
                    fetchFlow(connection, records).buffer().collect { seen += it }
                    seen
                }

            println("#   %-30s %,12.0f records/s".format("bare fetch loop", bare))
            println("#   %-30s %,12.0f records/s".format("same loop in a cold flow { }", cold))
            println("#   %-30s %,12.0f records/s".format("... plus buffer() between them", buffered))
            println()
            println("#   Flow machinery alone: %+.1f%%".format(100 * (cold - bare) / bare))
            println("#   decoupling on top:    %+.1f%%".format(100 * (buffered - cold) / cold))
            println()
        }
    }

    /** The same fetch loop as the bare one, expressed as a cold flow of batch sizes. */
    private fun fetchFlow(
        connection: BooblikConnection,
        records: Int,
    ) = kotlinx.coroutines.flow.flow {
        var seen = 0
        var position = Offset.ZERO
        while (seen < records) {
            val answer = connection.fetch(TOPIC, PartitionId(0), position, MAX_BYTES)
            if (answer.records.isEmpty()) break
            seen += answer.records.size
            position += answer.records.size.toLong()
            emit(answer.records.size)
        }
    }

    private suspend fun preload(
        address: InetSocketAddress,
        records: Int,
    ) {
        BooblikClient(address).use { client ->
            var written = 0
            while (written < records) {
                val batch = List(minOf(500, records - written)) { ByteArray(RECORD_SIZE) { b -> b.toByte() } }
                client.sendProduce(TOPIC, PartitionId(0), batch)
                client.receiveProduce()
                written += batch.size
            }
        }
        println("# preloaded $records records")
    }

    /** Records per second, with a ceiling so a stalled read fails instead of hanging the probe. */
    private suspend fun timed(
        expected: Int,
        body: suspend CoroutineScope.() -> Int,
    ): Double =
        kotlinx.coroutines.coroutineScope {
            val began = System.nanoTime()
            val seen = withTimeoutOrNull(120_000) { body() } ?: error("read did not finish in two minutes")
            check(seen >= expected) { "read $seen records, expected $expected" }
            seen * 1e9 / (System.nanoTime() - began)
        }

    private object NoopStore : OffsetStore {
        override suspend fun load(
            topic: TopicName,
            partition: PartitionId,
        ): Offset? = null

        override suspend fun save(
            topic: TopicName,
            partition: PartitionId,
            offset: Offset,
        ) = Unit
    }
}
