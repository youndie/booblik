package ru.workinprogress.booblik.benchmark.probe

import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.FileStore
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * M-26: what happens to the mapped write path once the log stops fitting in memory.
 *
 * Risk 1 of the research document, stated there as a prediction: a short benchmark on a fresh
 * temporary directory is the most flattering case a memory mapping will ever see, because every
 * page it dirties is a page the kernel has not been asked to write back yet. Sooner or later the
 * writer outruns writeback and starts waiting for it, and the prediction was that the mapped path
 * degrades worse than the plain one — in a step rather than a slope.
 *
 * So this does not report an average. It reports **throughput per second, second by second**, for
 * long enough to write several times the machine's memory. An average would hide exactly the shape
 * the question is about.
 *
 * ## Why this does not fill the disk
 *
 * Retention runs in the loop and keeps the live log at [RETAINED_BYTES]; total bytes written go far
 * past that, but bytes on disk do not. The probe also refuses to start unless the filesystem has
 * several times that much free, because a benchmark that fills someone's disk is not a benchmark.
 */
object SustainedWriteProbe {
    private const val RECORD_SIZE = 1024
    private const val SEGMENT_CAPACITY = 128 * 1024 * 1024
    private const val RETAINED_BYTES = 512L * 1024 * 1024
    private const val REQUIRED_FREE_BYTES = 4L * 1024 * 1024 * 1024
    private const val DEFAULT_SECONDS = 60

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = SegmentMode.valueOf(args.getOrElse(0) { SegmentMode.MAPPED.name })
        val seconds = args.getOrElse(1) { DEFAULT_SECONDS.toString() }.toInt()

        val dir = Files.createTempDirectory("booblik-sustained")
        try {
            val store: FileStore = Files.getFileStore(dir)
            val free = store.usableSpace
            check(free >= REQUIRED_FREE_BYTES) {
                "refusing to run: ${free / 1024 / 1024} MiB free on ${store.name()}, " +
                    "need at least ${REQUIRED_FREE_BYTES / 1024 / 1024} MiB"
            }

            val heap = Runtime.getRuntime().maxMemory()
            println("# M-26 sustained write probe: mode=$mode, ${seconds}s, records of $RECORD_SIZE B")
            println("# retaining at most ${RETAINED_BYTES / 1024 / 1024} MiB on disk; ${free / 1024 / 1024} MiB free")
            // The heap figure is here to be dismissed, not to be used: the page cache this probe
            // fights with is bounded by the machine's RAM, and the mapping the mapped path writes
            // through is not heap at all. `-Xmx` is the wrong number to reason with, and printing
            // it next to the right one is the quickest way to stop someone reaching for it.
            println(
                "# heap %d MiB (irrelevant: a mapping is not heap); RAM %.1f GiB (what actually bounds writeback)"
                    .format(heap / 1024 / 1024, physicalMemoryBytes() / 1024.0 / 1024 / 1024),
            )
            println()
            println("second   records/s        MiB/s   segments")

            val record = ByteArray(RECORD_SIZE) { it.toByte() }
            var totalRecords = 0L
            val windows = ArrayList<Double>()

            PartitionLog.open(dir, mode, SEGMENT_CAPACITY).use { log ->
                val started = System.nanoTime()
                var windowStart = started
                var windowRecords = 0L

                while (System.nanoTime() - started < seconds * 1_000_000_000L) {
                    log.append(record)
                    windowRecords += 1
                    totalRecords += 1

                    // Checked every so often rather than every record: `nanoTime` is cheap but not
                    // free, and at these rates calling it per append would be a visible tax on the
                    // thing being measured.
                    if (windowRecords % CHECK_INTERVAL == 0L) {
                        log.retainAtMost(RETAINED_BYTES)
                        val now = System.nanoTime()
                        val elapsed = now - windowStart
                        if (elapsed >= 1_000_000_000L) {
                            val rate = windowRecords * 1e9 / elapsed
                            windows += rate
                            println(
                                "%6d %11.0f %12.1f %10d".format(
                                    (now - started) / 1_000_000_000L,
                                    rate,
                                    rate * RECORD_SIZE / 1024 / 1024,
                                    log.segmentCount,
                                ),
                            )
                            windowStart = now
                            windowRecords = 0
                        }
                    }
                }
            }

            val totalBytes = totalRecords * RECORD_SIZE
            val sorted = windows.sorted()
            println()
            println("# summary (mode=$mode)")
            println(
                "#   mean %.0f rec/s   median %.0f   worst second %.0f   best second %.0f".format(
                    windows.average(),
                    sorted[sorted.size / 2],
                    sorted.first(),
                    sorted.last(),
                ),
            )
            // The ratio is the whole point of running this for a minute instead of two seconds. A
            // path whose worst second is close to its best one can be planned around; a path that
            // swings by an order of magnitude cannot, whatever its average says.
            println("#   best/worst = %.1fx".format(sorted.last() / sorted.first()))
            println(
                "#   wrote %.1f GiB, %.1f times the %.1f GiB of RAM on this machine".format(
                    totalBytes / 1024.0 / 1024 / 1024,
                    totalBytes / physicalMemoryBytes(),
                    physicalMemoryBytes() / 1024.0 / 1024 / 1024,
                ),
            )
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private const val CHECK_INTERVAL = 4096L

    /**
     * Physical RAM, via the JDK's own platform bean rather than a shell out to `sysctl`.
     * Falls back to the heap size if the platform will not say, which only makes the reported
     * multiple conservative.
     */
    private fun physicalMemoryBytes(): Double {
        val bean =
            java.lang.management.ManagementFactory
                .getOperatingSystemMXBean()
        return (bean as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
            ?.toDouble()
            ?: Runtime.getRuntime().maxMemory().toDouble()
    }
}
