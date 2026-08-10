package ru.workinprogress.booblik.benchmark.probe

import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.system.measureNanoTime

/**
 * M-23: is rebuilding the index by scanning the segments fast enough to go on doing it, or does the
 * broker need index files on disk?
 *
 * The open question in the research document was left with a guess attached — that a scan would
 * turn into minutes once there were a few dozen segments — and a guess is not a reason to build a
 * second file format. This measures the scan on a log of a realistic size and reports the rate, so
 * the decision is arithmetic.
 *
 * The rate is what matters, not the total: a broker's startup cost is (bytes of log) ÷ (scan rate),
 * and the number of segments only matters through the bytes in them.
 */
object StartupProbe {
    private const val RECORD_SIZE = 128
    private const val SEGMENT_CAPACITY = 16 * 1024 * 1024
    private const val SEGMENTS = 16

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = SegmentMode.valueOf(args.getOrElse(0) { SegmentMode.FILE_CHANNEL.name })
        val dir = Files.createTempDirectory("booblik-startup")
        try {
            val record = ByteArray(RECORD_SIZE) { it.toByte() }
            val totalBytes = SEGMENTS.toLong() * SEGMENT_CAPACITY

            println("# M-23 startup probe: mode=$mode, ${totalBytes / 1024 / 1024} MiB across $SEGMENTS segments")

            var records = 0L
            val writeNanos =
                measureNanoTime {
                    PartitionLog.open(dir, mode, SEGMENT_CAPACITY).use { log ->
                        while (log.segmentCount < SEGMENTS) {
                            log.append(record)
                            records += 1
                        }
                    }
                }
            println("# wrote $records records in %.1f s".format(writeNanos / 1e9))

            // Reopened three times: the first pays for a cold page cache, the rest do not, and the
            // difference between them is the honest range a restart falls into. Reporting only the
            // warm number would describe a restart that never happens — nobody restarts a broker
            // twice in a row — and only the cold one would blame the scan for the disk.
            repeat(3) { attempt ->
                val nanos =
                    measureNanoTime {
                        PartitionLog.open(dir, mode, SEGMENT_CAPACITY).use { log ->
                            check(log.nextOffset.value == records) {
                                "recovery lost records: ${log.nextOffset.value} != $records"
                            }
                        }
                    }
                val seconds = nanos / 1e9
                println(
                    "attempt %d: recovered in %6.3f s  =  %6.0f MiB/s  =  %5.2f s per GiB of log".format(
                        attempt + 1,
                        seconds,
                        totalBytes / 1024.0 / 1024.0 / seconds,
                        seconds * (1024.0 * 1024 * 1024) / totalBytes,
                    ),
                )
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }
}
