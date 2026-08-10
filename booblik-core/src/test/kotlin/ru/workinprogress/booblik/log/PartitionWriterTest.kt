package ru.workinprogress.booblik.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.storage.LogSegment
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartitionWriterTest {
    private fun <T> withWriter(
        mode: SegmentMode = SegmentMode.FILE_CHANNEL,
        body: suspend CoroutineScope.(PartitionWriter, LogSegment) -> T,
    ): T {
        val dir = Files.createTempDirectory("booblik-writer")
        return try {
            LogSegment.open(dir, Offset.ZERO, mode, capacity = 1 shl 20).use { segment ->
                runBlocking {
                    coroutineScope {
                        val writer = PartitionWriter(segment, this)
                        val result = body(writer, segment)
                        writer.close()
                        result
                    }
                }
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a batch gets one acknowledgement and consecutive offsets`() {
        withWriter { writer, segment ->
            val batch = (0 until 5).map { "record-$it".toByteArray() }
            val base = writer.append(batch)

            assertEquals(Offset.ZERO, base)
            assertEquals(Offset(5), segment.nextOffset)
            batch.forEachIndexed { i, expected ->
                assertContentEquals(expected, segment.read(Offset(i.toLong())))
            }
        }
    }

    @Test
    fun `batches keep their order and their offsets do not interleave`() {
        withWriter { writer, segment ->
            val first = writer.append(listOf("a".toByteArray(), "b".toByteArray()))
            val second = writer.append(listOf("c".toByteArray()))

            assertEquals(Offset(0), first)
            assertEquals(Offset(2), second)
            assertEquals(Offset(3), segment.nextOffset)
        }
    }

    @Test
    fun `NONE does not report an offset and still writes`() {
        // The record is written, but the caller is told nothing — there is no offset to tell it,
        // because the offset does not exist until the actor gets to the batch.
        val dir = Files.createTempDirectory("booblik-writer-none")
        try {
            LogSegment.open(dir, Offset.ZERO, capacity = 1 shl 20).use { segment ->
                runBlocking {
                    val writer = PartitionWriter(segment, this)
                    assertNull(writer.append("fire and forget".toByteArray(), AckPolicy.NONE))
                    writer.close()
                    assertEquals(Offset(1), segment.nextOffset)
                    assertContentEquals("fire and forget".toByteArray(), segment.read(Offset.ZERO))
                }
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `FORCED asks the log for a barrier, WRITTEN does not`() {
        val log = CountingLog()
        runBlocking {
            val writer = PartitionWriter(log, this)
            writer.append("one".toByteArray(), AckPolicy.WRITTEN)
            assertEquals(0, log.forceCount, "WRITTEN must not pay for a barrier")
            writer.append("two".toByteArray(), AckPolicy.FORCED)
            assertEquals(1, log.forceCount)
            writer.close()
        }
    }

    @Test
    fun `group commit amortises one barrier across many producers`() {
        // Not flaky by construction: the barrier here takes milliseconds while queueing a batch
        // takes microseconds, so by the time the first `force` returns essentially every other
        // producer is already in the mailbox and joins one group. The assertion leaves an order of
        // magnitude of slack rather than pinning an exact group count, which would depend on
        // scheduling.
        val producers = 200
        val log = CountingLog(forceMillis = 5)
        runBlocking {
            val writer = PartitionWriter(log, CoroutineScope(Dispatchers.Default))
            (0 until producers)
                .map { i -> async(Dispatchers.Default) { writer.append("p-$i".toByteArray(), AckPolicy.FORCED) } }
                .awaitAll()
            writer.close()
        }

        assertEquals(producers, log.appendCount)
        assertTrue(
            log.forceCount < producers / 10,
            "expected group commit to collapse $producers barriers into a handful, got ${log.forceCount}",
        )
    }

    @Test
    fun `concurrent producers get unique, gap-free offsets`() {
        // M-13. Every offset handed out must be distinct and the set must be exactly 0 until N:
        // a lost update shows up as a duplicate, a torn counter as a gap.
        val producers = 64
        val batchesEach = 25
        val recordsPerBatch = 4

        val bases =
            withWriter { writer, segment ->
                val result =
                    (0 until producers)
                        .map { p ->
                            async(Dispatchers.Default) {
                                (0 until batchesEach).map { b ->
                                    writer.append(List(recordsPerBatch) { "p$p-b$b-$it".toByteArray() })!!
                                }
                            }
                        }.awaitAll()
                        .flatten()
                writer.close()
                assertEquals(Offset((producers * batchesEach * recordsPerBatch).toLong()), segment.nextOffset)
                result
            }

        val expected = (0 until producers * batchesEach).map { (it * recordsPerBatch).toLong() }.toSet()
        assertEquals(expected, bases.map { it.value }.toSet(), "base offsets must be distinct and gap-free")
    }
}
