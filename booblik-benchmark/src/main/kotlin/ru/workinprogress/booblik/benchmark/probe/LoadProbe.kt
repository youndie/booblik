package ru.workinprogress.booblik.benchmark.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.workinprogress.booblik.benchmark.MeasurementDir
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.BooblikServer
import ru.workinprogress.booblik.net.FetchMode
import ru.workinprogress.booblik.net.PartitionHandle
import ru.workinprogress.booblik.net.PartitionRegistry
import ru.workinprogress.booblik.net.ServerConfig
import ru.workinprogress.booblik.net.Transport
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * M-33/M-34: end-to-end throughput and latency against a real broker over a real socket.
 *
 * Broker and load generator share this JVM and its heap, which is a known contaminant and the
 * reason GC counters are printed next to the percentiles (M-37). The generator itself lives in
 * [LoadDriver]; what this probe adds is a broker to point it at.
 *
 * For the same measurement across two machines — which is what a question about the *network* needs
 * — see [RemoteLoadProbe].
 *
 * Usage: `-Pargs="PRODUCE SELECTOR ZERO_COPY <connections> <ratePerSecond> <seconds>"`.
 */
object LoadProbe {
    private const val SEGMENT_CAPACITY = 128 * 1024 * 1024

    @JvmStatic
    fun main(args: Array<String>) {
        val workload = LoadDriver.Workload.valueOf(args.getOrElse(0) { "PRODUCE" })
        val transport = Transport.valueOf(args.getOrElse(1) { "SELECTOR" })
        val fetchMode = FetchMode.valueOf(args.getOrElse(2) { "ZERO_COPY" })
        val connections = args.getOrElse(3) { "8" }.toInt()
        val targetRate = args.getOrElse(4) { "20000" }.toInt()
        val seconds = args.getOrElse(5) { "20" }.toLong()
        // A knob because it turned out to matter: a segment that fills during the run puts a roll
        // inside the measurement, and a roll is visible in the tail.
        val segmentCapacity = args.getOrElse(6) { SEGMENT_CAPACITY.toString() }.toInt()
        // M-44. Depth 1 is strict request-response: the sender may not issue the next request until
        // the previous answer is in. Anything higher is pipelining.
        val pipelineDepth = args.getOrElse(7) { LoadDriver.MAX_IN_FLIGHT.toString() }.toInt()
        // The write path was hardcoded to FILE_CHANNEL here, which meant the end-to-end numbers
        // measured a broker nobody would run once the default changed (M-45). It follows the
        // default now, and can be overridden to compare the two through the socket rather than
        // only at the storage layer.
        val segmentMode = SegmentMode.valueOf(args.getOrElse(8) { SegmentMode.MAPPED.name })

        val dir = MeasurementDir.create("booblik-load")
        val scope = CoroutineScope(SupervisorJob())
        val log = PartitionLog.open(dir, segmentMode, segmentCapacity)
        val writer = PartitionWriter(log, scope)
        val server =
            BooblikServer(
                PartitionRegistry.of(
                    PartitionRegistry.Key(LoadDriver.TOPIC, LoadDriver.PARTITION) to PartitionHandle(log, writer),
                ),
                ServerConfig(port = 0, transport = transport, fetchMode = fetchMode),
            )

        try {
            val address = server.start()
            println("# M-33/M-34 load probe: $workload, $transport, fetch=$fetchMode, segments=$segmentMode")
            println("# storage: ${MeasurementDir.describe(dir)}")
            println(
                "# $connections connections, target $targetRate/s total, ${seconds}s, pipeline depth $pipelineDepth",
            )
            println(
                "# records of ${LoadDriver.RECORD_SIZE} B, batches of ${LoadDriver.BATCH_SIZE}; " +
                    "segment capacity ${segmentCapacity / 1024 / 1024} MiB",
            )

            if (workload == LoadDriver.Workload.FETCH) LoadDriver.preload(address)

            val gcBefore = LoadDriver.gcSnapshot()
            val result = LoadDriver.drive(workload, address, connections, targetRate, seconds, pipelineDepth)
            val gcAfter = LoadDriver.gcSnapshot()

            val achieved = result.completed.toDouble() / seconds
            println()
            println("# result")
            println("#   target        %,10.0f requests/s".format(targetRate.toDouble()))
            println(
                "#   achieved      %,10.0f requests/s  (%.0f%% of target)".format(
                    achieved,
                    100 * achieved / targetRate,
                ),
            )
            if (workload == LoadDriver.Workload.PRODUCE) {
                println("#   records       %,10.0f records/s".format(achieved * LoadDriver.BATCH_SIZE))
            }
            println("#   completed     %,10d requests, log end offset ${log.nextOffset}".format(result.completed))
            LoadDriver.reportLatency(result.histogram)

            val collections = gcAfter.first - gcBefore.first
            val millis = gcAfter.second - gcBefore.second
            println("#")
            println(
                "#   GC during the run: %d collections, %d ms total (%.1f%% of wall clock)".format(
                    collections,
                    millis,
                    100.0 * millis / (seconds * 1000),
                ),
            )
            println("#   note: the load generator shares this JVM and its heap with the broker (M-37)")
        } finally {
            server.close()
            scope.cancel()
            log.close()
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }
}
