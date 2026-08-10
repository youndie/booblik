package ru.workinprogress.booblik.storage

import ru.workinprogress.booblik.Offset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M-61: the invariant that has to hold for every shape of input, not just the ones somebody thought
 * of while writing an example.
 *
 * ## Why randomised and not a property library
 *
 * The properties here are two lines each; a framework to express them would be more machinery than
 * the thing it expresses. What a framework does buy is shrinking — a minimal failing case rather
 * than a huge one — and that is worth losing to keep the dependency out, given that the seed is
 * printed on failure and reproduces the case exactly.
 *
 * **Every failure message carries its seed.** A randomised test that cannot be replayed is a
 * randomised test that gets deleted the first time it fails on CI.
 */
class LogPropertiesTest {
    private fun <T> withDir(body: (Path) -> T): T {
        val dir = Files.createTempDirectory("booblik-properties")
        return try {
            body(dir)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    /** A fresh seed per run, printed so any failure is reproducible by pasting it back. */
    private fun seeds(count: Int): List<Long> = List(count) { Random.nextLong() }

    @Test
    fun `whatever is written comes back, at any record size and any segment boundary`() {
        for (seed in seeds(ROUNDS)) {
            val random = Random(seed)
            val mode = SegmentMode.entries.random(random)
            // Small segments on purpose: the interesting cases are at the boundaries, and a large
            // segment simply never reaches one.
            val capacity = random.nextInt(512, 8 * 1024)
            val records =
                List(random.nextInt(1, 200)) {
                    // Bounded so a record always fits an empty segment — a record larger than that
                    // is rejected by design and is covered by its own test.
                    ByteArray(random.nextInt(1, capacity / 4)) { random.nextInt().toByte() }
                }

            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = capacity).use { log ->
                    records.forEach { log.append(it) }

                    assertEquals(
                        Offset(records.size.toLong()),
                        log.nextOffset,
                        "seed=$seed mode=$mode capacity=$capacity: offsets are gap-free",
                    )
                    records.forEachIndexed { i, expected ->
                        assertContentEquals(
                            expected,
                            log.read(Offset(i.toLong())),
                            "seed=$seed mode=$mode capacity=$capacity: record $i of ${records.size}",
                        )
                    }
                    assertNull(
                        log.read(Offset(records.size.toLong())),
                        "seed=$seed: reading past the end must be null, not garbage",
                    )
                }
            }
        }
    }

    @Test
    fun `a reopened log says exactly what it said before`() {
        for (seed in seeds(ROUNDS)) {
            val random = Random(seed)
            val mode = SegmentMode.entries.random(random)
            val capacity = random.nextInt(512, 8 * 1024)
            val records =
                List(random.nextInt(1, 200)) {
                    ByteArray(random.nextInt(1, capacity / 4)) { random.nextInt().toByte() }
                }

            withDir { dir ->
                PartitionLog.open(dir, mode, segmentCapacity = capacity).use { log ->
                    records.forEach { log.append(it) }
                }
                PartitionLog.open(dir, mode, segmentCapacity = capacity).use { reopened ->
                    assertEquals(
                        Offset(records.size.toLong()),
                        reopened.nextOffset,
                        "seed=$seed mode=$mode capacity=$capacity: recovery lost or invented records",
                    )
                    records.forEachIndexed { i, expected ->
                        assertContentEquals(
                            expected,
                            reopened.read(Offset(i.toLong())),
                            "seed=$seed mode=$mode capacity=$capacity: record $i after recovery",
                        )
                    }
                    // And it keeps going from where it stopped.
                    assertEquals(
                        Offset(records.size.toLong()),
                        reopened.append("one more".toByteArray()),
                        "seed=$seed",
                    )
                }
            }
        }
    }

    @Test
    fun `truncating to any point leaves exactly the records below it`() {
        for (seed in seeds(ROUNDS)) {
            val random = Random(seed)
            val mode = SegmentMode.entries.random(random)
            val records =
                List(random.nextInt(2, 150)) {
                    ByteArray(random.nextInt(1, 300)) { random.nextInt().toByte() }
                }
            val cut = random.nextInt(0, records.size)

            withDir { dir ->
                LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                    records.forEach { segment.append(it) }
                    segment.truncateTo(Offset(cut.toLong()))

                    assertEquals(Offset(cut.toLong()), segment.nextOffset, "seed=$seed mode=$mode cut=$cut")
                    for (i in 0 until cut) {
                        assertContentEquals(
                            records[i],
                            segment.read(Offset(i.toLong())),
                            "seed=$seed mode=$mode cut=$cut: record $i below the cut",
                        )
                    }
                    assertNull(segment.read(Offset(cut.toLong())), "seed=$seed: the cut offset is gone")
                    // The offset the cut freed is handed out again, not skipped.
                    assertEquals(
                        Offset(cut.toLong()),
                        segment.append("reused".toByteArray()),
                        "seed=$seed mode=$mode cut=$cut",
                    )
                }
            }
        }
    }

    private companion object {
        /** Enough shapes to be worth running, few enough to keep `check` quick. */
        const val ROUNDS = 25
    }
}
