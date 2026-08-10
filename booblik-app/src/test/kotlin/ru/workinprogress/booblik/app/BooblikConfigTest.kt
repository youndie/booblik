package ru.workinprogress.booblik.app

import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.net.FetchMode
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M-50: a typo in configuration should stop the broker from booting, not surface at 3am. */
class BooblikConfigTest {
    private fun <T> withFile(
        text: String,
        body: (java.nio.file.Path) -> T,
    ): T {
        val dir = Files.createTempDirectory("booblik-config")
        return try {
            val file = dir.resolve("broker.properties")
            file.writeText(text)
            body(file)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `defaults are enough to start`() {
        val config = BooblikConfig.load(env = emptyMap())
        assertEquals(BooblikConfig.DEFAULT_PORT, config.port)
        assertEquals(mapOf(TopicName("default") to 1), config.topics)
        assertEquals(SegmentMode.FILE_CHANNEL, config.segmentMode)
        assertTrue(!config.flushPolicy.isEnabled, "no flush policy unless somebody chose a window")
        assertNull(config.retentionBytes, "nothing is deleted unless somebody asked")
    }

    @Test
    fun `the environment beats the file`() {
        withFile("booblik.port=1111\nbooblik.topics=fromfile:2\n") { file ->
            val config = BooblikConfig.load(file, env = mapOf("BOOBLIK_PORT" to "2222"))
            assertEquals(2222, config.port, "environment wins")
            assertEquals(mapOf(TopicName("fromfile") to 2), config.topics, "file still supplies the rest")
        }
    }

    @Test
    fun `topics parse into partition counts`() {
        withFile("booblik.topics=orders:3, clicks:1\n") { file ->
            assertEquals(
                mapOf(TopicName("orders") to 3, TopicName("clicks") to 1),
                BooblikConfig.load(file, env = emptyMap()).topics,
            )
        }
    }

    @Test
    fun `a nonsense value refuses to boot rather than falling back`() {
        // Silently using a default in place of something somebody typed is how a broker ends up
        // running a configuration nobody chose.
        withFile("booblik.port=nine-thousand\n") { file ->
            assertFailsWith<IllegalStateException> { BooblikConfig.load(file, env = emptyMap()) }
        }
        withFile("booblik.segment.mode=MMAP\n") { file ->
            val failure = assertFailsWith<IllegalStateException> { BooblikConfig.load(file, env = emptyMap()) }
            assertTrue(failure.message!!.contains("FILE_CHANNEL"), "the message names the valid values")
        }
        withFile("booblik.topics=orders\n") { file ->
            assertFailsWith<IllegalArgumentException> { BooblikConfig.load(file, env = emptyMap()) }
        }
        withFile("booblik.segment.capacity.bytes=0\n") { file ->
            assertFailsWith<IllegalArgumentException> { BooblikConfig.load(file, env = emptyMap()) }
        }
    }

    @Test
    fun `a named config file that does not exist is an error`() {
        assertFailsWith<IllegalArgumentException> {
            BooblikConfig.load(
                java.nio.file.Path
                    .of("/nonexistent/broker.properties"),
                env = emptyMap(),
            )
        }
    }

    @Test
    fun `flush policy and retention come through`() {
        withFile(
            """
            booblik.flush.every.records=1000
            booblik.flush.every.millis=200
            booblik.retention.bytes=1048576
            booblik.fetch.mode=HEAP
            """.trimIndent(),
        ) { file ->
            val config = BooblikConfig.load(file, env = emptyMap())
            assertEquals(1000L, config.flushPolicy.everyRecords)
            assertEquals(200L, config.flushPolicy.everyMillis)
            assertEquals(1_048_576L, config.retentionBytes)
            assertEquals(FetchMode.HEAP, config.fetchMode)
        }
    }
}
