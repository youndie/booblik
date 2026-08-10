package ru.workinprogress.booblik.benchmark.probe

import java.net.InetSocketAddress

/**
 * M-38: the same load, but against a broker on **another machine**.
 *
 * ## Why a separate probe exists at all
 *
 * [LoadProbe] answers questions about the broker; this one answers a question about the **network**,
 * and the two cannot be the same program. On loopback the kernel copies the payload regardless of
 * how the broker handed it over, so `sendfile` and a read-into-heap loop end up doing comparable
 * work and M-35 measured a difference of nothing below 1 GiB/s. Whether that is a property of
 * zero-copy or a property of loopback is not decidable from inside one machine.
 *
 * ## What it measures, and why bytes rather than requests
 *
 * Zero-copy saves *per byte*, so the axis it can move is bytes per second. A request count says
 * nothing about it without the record size next to it, and the previous end-to-end numbers were
 * all in requests. This probe reports both, plus the achieved bandwidth in MiB/s, so the result can
 * be put next to `iperf3` for the same link — which is the only way to tell "the broker is the
 * bottleneck" from "the wire is the bottleneck", and those two have opposite conclusions.
 *
 * The broker's own CPU is **not** measured here and deliberately so: it runs in another process on
 * another host, and the honest way to read it is from that host's `/proc`, outside this JVM. When
 * bandwidth saturates, CPU per delivered byte is the only axis left, and a number this process
 * invented about a process it cannot see would be worth nothing.
 *
 * Usage: `-Pargs="<host> <port> FETCH <connections> <ratePerSecond> <seconds> [pipelineDepth]
 * [fetchMaxBytes] [preload]"`. The broker is expected to be running already, with its own
 * `booblik.fetch.mode` — which is the setting under test, and it lives on the other side.
 */
object RemoteLoadProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val host = args.getOrElse(0) { error("usage: <host> <port> [FETCH|PRODUCE] ...") }
        val port = args.getOrElse(1) { "9092" }.toInt()
        val workload = LoadDriver.Workload.valueOf(args.getOrElse(2) { "FETCH" })
        val connections = args.getOrElse(3) { "8" }.toInt()
        val targetRate = args.getOrElse(4) { "20000" }.toInt()
        val seconds = args.getOrElse(5) { "20" }.toLong()
        val pipelineDepth = args.getOrElse(6) { LoadDriver.MAX_IN_FLIGHT.toString() }.toInt()
        val fetchMaxBytes = args.getOrElse(7) { LoadDriver.FETCH_MAX_BYTES.toString() }.toInt()
        // Whether to fill the log first. Off by default: the broker keeps its data between runs, so
        // preloading once and then comparing several fetch modes against the same log is both faster
        // and fairer than reloading in between.
        val preload = args.getOrElse(8) { "false" }.toBoolean()
        val preloadedRecords = args.getOrElse(9) { LoadDriver.PRELOAD_RECORDS.toString() }.toInt()
        // Record size is a knob here and not in the in-process probe, because it is the difference
        // between measuring the wire and measuring the client. At 128 B a 64 KiB response carries
        // ~480 records, and the client allocates and checksums every one of them — so at a gigabyte
        // a second the harness saturates before the link does, and the answer would be about the
        // harness. Bigger records move the bottleneck back to where the question is.
        val recordSize = args.getOrElse(10) { LoadDriver.RECORD_SIZE.toString() }.toInt()

        val address = InetSocketAddress(host, port)
        println("# M-38 remote load probe: $workload against $host:$port")
        println("# host JVM: ${System.getProperty("java.vm.version")}, ${System.getProperty("os.name")}")
        println("# $connections connections, target $targetRate/s total, ${seconds}s, pipeline depth $pipelineDepth")
        println("# fetch maxBytes ${fetchMaxBytes / 1024} KiB; records of $recordSize B")
        println("# this process is the CLIENT ONLY — the broker runs elsewhere, so nothing here shares its heap")

        if (preload) LoadDriver.preload(address, preloadedRecords, recordSize)

        val result =
            LoadDriver.drive(
                workload,
                address,
                connections,
                targetRate,
                seconds,
                pipelineDepth,
                fetchMaxBytes,
                preloadedRecords,
            )

        val achieved = result.completed.toDouble() / seconds
        val mib = result.bytes.toDouble() / seconds / 1024 / 1024
        println()
        println("# result")
        println("#   target        %,10.0f requests/s".format(targetRate.toDouble()))
        println(
            "#   achieved      %,10.0f requests/s  (%.0f%% of target)".format(achieved, 100 * achieved / targetRate),
        )
        println("#   completed     %,10d requests".format(result.completed))
        println("#   bandwidth     %10.1f MiB/s  (%.2f Gbit/s of payload)".format(mib, mib * 8 / 1024))
        LoadDriver.reportLatency(result.histogram)
    }
}
