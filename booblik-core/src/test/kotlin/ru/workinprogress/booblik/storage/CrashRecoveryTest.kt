package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-60: kill a real process in the middle of writing and see what the log says afterwards.
 *
 * ## Why a subprocess and not a mock
 *
 * Every cheaper version of this test tests something else. Closing the segment tests `close`.
 * Throwing from a fake writer tests the fake. Truncating the file by hand tests the truncation.
 * The failure this has to survive is `SIGKILL` between an append and a barrier — no unwinding, no
 * flush, no finally block — and the only way to produce that is to have a process to kill.
 *
 * ## The invariant
 *
 * Whatever the timing, reopening must give a log that ends **on a record boundary**, with every
 * record it reports matching its own checksum, and appending must continue from there. It says
 * nothing about *how many* records survive: a writer that never forced can lose everything the
 * page cache was still holding, and that is the documented meaning of `WRITTEN`.
 */
class CrashRecoveryTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-crash")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** Records the writer must report before it is worth killing. */
        const val MINIMUM_BEFORE_KILL = 2_000L
    }

    private fun spawnWriter(
        dir: Path,
        mode: SegmentMode,
    ): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            CrashWriter::class.java.name,
            dir.toString(),
            mode.name,
        ).redirectErrorStream(true)
            .start()
    }

    /**
     * Waits until the writer reports having written [MINIMUM_BEFORE_KILL] records.
     *
     * Two earlier versions of this were wrong in instructive ways. Sleeping a fixed 300 ms killed
     * the process before it had started — JVM startup plus the test worker's overhead — and every
     * round recovered nothing, which looked exactly like a bug in recovery. Watching the file size
     * fixed that for `FILE_CHANNEL` and was still wrong for `MAPPED`: mapping pre-sizes the file to
     * its full capacity, so the size crosses any threshold **before the first record exists**.
     *
     * So the writer says what it has done, and the parent waits for that. A signal derived from
     * the thing being tested is worth more than one inferred from a side effect of it.
     */
    private fun awaitWriting(process: Process): Long {
        val reader = process.inputStream.bufferedReader()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            val written = line.removePrefix(CrashWriter.PROGRESS).trim().toLongOrNull() ?: continue
            if (written >= MINIMUM_BEFORE_KILL) return written
        }
        error("the writer never reported $MINIMUM_BEFORE_KILL records; exited=${!process.isAlive}")
    }

    @Test
    fun `killing the writer leaves a log that ends on a record boundary`() {
        // Several rounds with different kill timings: the interesting moment is somewhere inside a
        // write, and there is no way to aim at it, so the test takes several shots.
        for (mode in SegmentMode.entries) {
            for (round in 0 until 3) {
                withDir { dir ->
                    val process = spawnWriter(dir, mode)
                    awaitWriting(process)
                    // A little longer, varied per round, so the kill lands at a different point in
                    // a record each time.
                    Thread.sleep(20L + round * 37L)
                    process.destroyForcibly()
                    process.waitFor()

                    LogSegment.open(dir, Offset.ZERO, mode, capacity = CrashWriter.CAPACITY).use { recovered ->
                        val end = recovered.nextOffset.value
                        assertTrue(end > 0, "mode=$mode round=$round: nothing survived at all")

                        // Every record it admits to having must be readable and must match its
                        // checksum — `read` throws otherwise.
                        for (offset in 0 until end) {
                            val record = recovered.read(Offset(offset))
                            assertContentEquals(
                                CrashWriter.payload(offset),
                                record,
                                "mode=$mode round=$round: record $offset came back wrong",
                            )
                        }

                        // And the log is usable: the next append continues from the boundary
                        // recovery chose, rather than from somewhere inside a half-written record.
                        assertEquals(
                            Offset(end),
                            recovered.append("after the crash".toByteArray()),
                            "mode=$mode round=$round",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Writes records until something kills it. Run as a subprocess by [CrashRecoveryTest].
 *
 * Deliberately never forces and never closes: the point is to be killed with data in flight.
 */
object CrashWriter {
    const val CAPACITY = 64 * 1024 * 1024

    /** Prefix of the progress line the parent waits for. */
    const val PROGRESS = "written"

    private const val REPORT_EVERY = 500L

    /** Deterministic content, so the parent can check any record it finds without bookkeeping. */
    fun payload(offset: Long): ByteArray = "record-$offset".repeat(4).toByteArray()

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val mode = SegmentMode.valueOf(args[1])
        val segment = LogSegment.open(dir, Offset.ZERO, mode, capacity = CAPACITY)
        var offset = 0L
        while (true) {
            val record = payload(offset)
            if (!segment.hasRoomFor(record.size)) return
            segment.append(record)
            offset += 1
            // Progress is reported, not inferred. The parent has no other reliable way to know the
            // writer is past its first record — see `awaitWriting`.
            if (offset % REPORT_EVERY == 0L) {
                println("$PROGRESS $offset")
                System.out.flush()
            }
        }
    }
}
