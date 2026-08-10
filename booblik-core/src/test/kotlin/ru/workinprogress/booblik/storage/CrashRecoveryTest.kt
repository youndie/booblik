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
        /** Enough that the writer is demonstrably going, small enough not to slow the test down. */
        const val MINIMUM_BEFORE_KILL = 64L * 1024
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
     * Waits until the segment file has actually grown, then returns.
     *
     * The first version of this test slept a fixed 300 ms and then killed the process, and every
     * round recovered nothing: between JVM startup and the test worker's own overhead, the writer
     * had not begun. Sleeping longer would have papered over it — the fix is to stop guessing and
     * watch the file, which also means the test cannot silently degrade into "killed a process that
     * was not writing" on a slower machine.
     */
    private fun awaitWriting(
        dir: Path,
        process: Process,
    ) {
        val file = dir.resolve(LogSegment.fileName(Offset.ZERO))
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.size(file) > MINIMUM_BEFORE_KILL) return
            check(process.isAlive) {
                "the writer died before writing anything:\n" + process.inputStream.readBytes().decodeToString()
            }
            Thread.sleep(10)
        }
        error("the writer never got going:\n" + process.inputStream.readBytes().decodeToString())
    }

    @Test
    fun `killing the writer leaves a log that ends on a record boundary`() {
        // Several rounds with different kill timings: the interesting moment is somewhere inside a
        // write, and there is no way to aim at it, so the test takes several shots.
        for (mode in SegmentMode.entries) {
            for (round in 0 until 3) {
                withDir { dir ->
                    val process = spawnWriter(dir, mode)
                    awaitWriting(dir, process)
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
        }
    }
}
