package ru.workinprogress.booblik.benchmark.probe

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.system.measureNanoTime

/**
 * M-24: does `msync` give the same guarantee as `fsync`, or is it only cheaper?
 *
 * The M0 benchmark found a barrier through a memory mapping to be about sixty times cheaper than a
 * barrier through a `FileChannel`, and treated that as a reason **not** to trust it: a number that
 * measures a weaker promise does not belong in the same table as one that measures a stronger
 * promise. This settles which it is.
 *
 * ## The experiment
 *
 * Timing the two calls side by side proves nothing on its own — they could differ for a dozen
 * innocent reasons. The decisive question is what a `fsync` finds left to do **after** an `msync`
 * has already returned:
 *
 * * if `msync` made the data durable, the `fsync` that follows it has nothing to write and should
 *   cost close to nothing;
 * * if `fsync` still costs its full price, then `msync` returned before the work was done, and the
 *   cheap number was cheap because it was buying less.
 *
 * Three cases are timed per round, each after dirtying the same amount of data:
 * `fsync` alone, `msync` alone, and `msync` immediately followed by `fsync`.
 *
 * This does not simulate power loss and cannot: no user-space program can. It measures which
 * layers still hold data when each call returns, which is the part that is decidable here.
 */
object DurabilityProbe {
    private const val DIRTY_BYTES = 32 * 1024 * 1024
    private const val ROUNDS = 9
    private const val WARMUP_ROUNDS = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Files.createTempDirectory("booblik-durability")
        try {
            println("# M-24 durability probe: ${DIRTY_BYTES / 1024 / 1024} MiB dirtied per round, $ROUNDS rounds")
            println("# host JVM: ${System.getProperty("java.vm.version")}, ${System.getProperty("os.name")}")

            val fsyncOnly = ArrayList<Long>()
            val msyncOnly = ArrayList<Long>()
            val msyncThenFsync = ArrayList<Long>()
            val fsyncAfterMsync = ArrayList<Long>()

            repeat(WARMUP_ROUNDS + ROUNDS) { round ->
                val measured = round >= WARMUP_ROUNDS

                channelRound(dir.resolve("chan-$round.dat")) { channel ->
                    val nanos = measureNanoTime { channel.force(false) }
                    if (measured) fsyncOnly += nanos
                }

                mappedRound(dir.resolve("map-a-$round.dat")) { segment, _ ->
                    val nanos = measureNanoTime { segment.force() }
                    if (measured) msyncOnly += nanos
                }

                mappedRound(dir.resolve("map-b-$round.dat")) { segment, channel ->
                    val msync = measureNanoTime { segment.force() }
                    val fsync = measureNanoTime { channel.force(false) }
                    if (measured) {
                        msyncThenFsync += msync + fsync
                        fsyncAfterMsync += fsync
                    }
                }
            }

            report("fsync alone (FileChannel path)", fsyncOnly)
            report("msync alone (mapped path)", msyncOnly)
            report("msync + fsync on the same file", msyncThenFsync)
            report("  of which the trailing fsync", fsyncAfterMsync)

            val msync = median(msyncOnly)
            val trailing = median(fsyncAfterMsync)
            val alone = median(fsyncOnly)
            println()
            println("# verdict")
            if (trailing > alone / 4) {
                println(
                    "# The fsync after an msync still costs ${percent(trailing, alone)} of a bare fsync.",
                )
                println("# msync therefore returns with work outstanding that fsync still has to do:")
                println("# it is not the same promise, and its ${percent(msync, alone)} price is not a discount.")
            } else {
                println(
                    "# The fsync after an msync costs only ${percent(trailing, alone)} of a bare fsync,",
                )
                println("# so msync had already done that work. On this filesystem the two barriers look")
                println("# equivalent -- which makes the mapped path's cheaper number a real saving.")
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** Dirties [DIRTY_BYTES] through an ordinary channel, then hands it to [barrier]. */
    private fun channelRound(
        file: java.nio.file.Path,
        barrier: (FileChannel) -> Unit,
    ) {
        FileChannel
            .open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
            .use { channel ->
                val chunk = ByteBuffer.allocateDirect(CHUNK)
                var written = 0
                while (written < DIRTY_BYTES) {
                    chunk.clear()
                    while (chunk.hasRemaining()) chunk.put(FILLER)
                    chunk.flip()
                    while (chunk.hasRemaining()) channel.write(chunk)
                    written += CHUNK
                }
                barrier(channel)
            }
    }

    /** Dirties [DIRTY_BYTES] through a mapping, then hands both views to [barrier]. */
    private fun mappedRound(
        file: java.nio.file.Path,
        barrier: (MemorySegment, FileChannel) -> Unit,
    ) {
        FileChannel
            .open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
            .use { channel ->
                Arena.ofShared().use { arena ->
                    val segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, DIRTY_BYTES.toLong(), arena)
                    var at = 0L
                    while (at < DIRTY_BYTES) {
                        segment.set(ValueLayout.JAVA_BYTE, at, FILLER)
                        at += 1
                    }
                    barrier(segment, channel)
                }
            }
    }

    private fun report(
        label: String,
        samples: List<Long>,
    ) {
        val median = median(samples)
        println(
            "%-34s median %8.2f ms   (min %.2f, max %.2f)".format(
                label,
                median / 1e6,
                (samples.minOrNull() ?: 0) / 1e6,
                (samples.maxOrNull() ?: 0) / 1e6,
            ),
        )
    }

    private fun median(samples: List<Long>): Long = samples.sorted()[samples.size / 2]

    private fun percent(
        part: Long,
        whole: Long,
    ): String = "%.0f%%".format(100.0 * part / whole)

    private const val CHUNK = 1 shl 20
    private const val FILLER: Byte = 0x5A
}
