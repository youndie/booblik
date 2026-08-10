package ru.workinprogress.booblik.app

import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.FlushPolicy
import ru.workinprogress.booblik.net.FetchMode
import ru.workinprogress.booblik.net.Transport
import ru.workinprogress.booblik.storage.LogSegment
import ru.workinprogress.booblik.storage.SegmentMode
import ru.workinprogress.booblik.storage.SparseOffsetIndex
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.inputStream

/**
 * Everything the broker needs to start.
 *
 * ## Why a properties file and not a config library
 *
 * There are perhaps fifteen settings here, and a library to read fifteen settings is a dependency
 * on the critical path of a process that is supposed to fit in 64 MiB. The parsing below is dull
 * on purpose; the interesting part is not how values are read but that every one of them is
 * **validated at startup**, so a typo is a refusal to boot rather than a surprise at three in the
 * morning.
 *
 * ## Precedence
 *
 * Environment beats file beats default. The environment name is the key upper-cased with dots
 * turned into underscores — `booblik.port` is `BOOBLIK_PORT` — because that is the mapping every
 * container runtime already assumes.
 */
data class BooblikConfig(
    val dataDir: Path,
    val port: Int,
    /** Topic to partition count. Fixed at startup: the broker does not create topics (M-42). */
    val topics: Map<TopicName, Int>,
    val segmentMode: SegmentMode,
    val segmentCapacity: Int,
    val indexIntervalBytes: Int,
    val flushPolicy: FlushPolicy,
    val retentionBytes: Long?,
    val retentionMillis: Long?,
    val retentionCheckMillis: Long,
    val transport: Transport,
    val fetchMode: FetchMode,
    val metricsIntervalMillis: Long,
) {
    init {
        require(port in 0..65535) { "booblik.port must be 0..65535, got $port" }
        require(topics.isNotEmpty()) { "booblik.topics must name at least one topic" }
        require(segmentCapacity > 0) { "booblik.segment.capacity.bytes must be positive" }
        require(indexIntervalBytes > 0) { "booblik.index.interval.bytes must be positive" }
        require(retentionCheckMillis > 0) { "booblik.retention.check.millis must be positive" }
    }

    /** One line an operator can paste into a ticket. Secrets would be redacted here; there are none. */
    fun describe(): String =
        buildString {
            appendLine("data.dir=$dataDir port=$port")
            appendLine("topics=" + topics.entries.joinToString(",") { "${it.key.value}:${it.value}" })
            appendLine("segment: mode=$segmentMode capacity=$segmentCapacity index.interval=$indexIntervalBytes")
            appendLine("flush: everyRecords=${flushPolicy.everyRecords} everyMillis=${flushPolicy.everyMillis}")
            appendLine("retention: bytes=$retentionBytes millis=$retentionMillis check=$retentionCheckMillis")
            append("net: transport=$transport fetch=$fetchMode metrics.interval=$metricsIntervalMillis")
        }

    companion object {
        /**
         * Loads from [file] if given, then lets the environment override.
         *
         * A missing file is an error when one was named and fine when none was: running with
         * nothing but defaults is a legitimate way to start, and silently ignoring a path somebody
         * typed is not.
         */
        fun load(
            file: Path? = null,
            env: Map<String, String> = System.getenv(),
        ): BooblikConfig {
            val properties = Properties()
            if (file != null) {
                require(
                    java.nio.file.Files
                        .exists(file),
                ) { "config file not found: $file" }
                file.inputStream().use(properties::load)
            }

            fun raw(key: String): String? =
                env[key.uppercase().replace('.', '_')]?.takeIf(String::isNotBlank)
                    ?: properties.getProperty(key)?.takeIf(String::isNotBlank)

            fun int(
                key: String,
                default: Int,
            ) = raw(key)?.toIntOrNull() ?: raw(key)?.let { error("$key is not an integer: $it") } ?: default

            fun long(
                key: String,
                default: Long,
            ) = raw(key)?.toLongOrNull() ?: raw(key)?.let { error("$key is not a number: $it") } ?: default

            fun longOrNull(key: String): Long? =
                raw(key)?.let { it.toLongOrNull() ?: error("$key is not a number: $it") }

            fun <T : Enum<T>> enum(
                key: String,
                default: T,
                values: Array<T>,
            ): T {
                val text = raw(key) ?: return default
                return values.firstOrNull { it.name.equals(text, ignoreCase = true) }
                    ?: error("$key must be one of ${values.joinToString(", ") { it.name }}, got $text")
            }

            return BooblikConfig(
                dataDir = Path(raw("booblik.data.dir") ?: "data"),
                port = int("booblik.port", DEFAULT_PORT),
                topics = parseTopics(raw("booblik.topics") ?: DEFAULT_TOPICS),
                segmentMode =
                    enum(
                        "booblik.segment.mode",
                        SegmentMode.MAPPED,
                        SegmentMode.entries.toTypedArray(),
                    ),
                segmentCapacity = int("booblik.segment.capacity.bytes", LogSegment.DEFAULT_CAPACITY),
                indexIntervalBytes = int("booblik.index.interval.bytes", SparseOffsetIndex.DEFAULT_INTERVAL_BYTES),
                flushPolicy =
                    FlushPolicy(
                        everyRecords = longOrNull("booblik.flush.every.records"),
                        everyMillis = longOrNull("booblik.flush.every.millis"),
                    ),
                retentionBytes = longOrNull("booblik.retention.bytes"),
                retentionMillis = longOrNull("booblik.retention.millis"),
                retentionCheckMillis = long("booblik.retention.check.millis", DEFAULT_RETENTION_CHECK_MILLIS),
                transport = enum("booblik.transport", Transport.SELECTOR, Transport.entries.toTypedArray()),
                fetchMode = enum("booblik.fetch.mode", FetchMode.ZERO_COPY, FetchMode.entries.toTypedArray()),
                metricsIntervalMillis = long("booblik.metrics.interval.millis", DEFAULT_METRICS_INTERVAL_MILLIS),
            )
        }

        /** `orders:3,clicks:1`. */
        private fun parseTopics(text: String): Map<TopicName, Int> =
            text
                .split(',')
                .filter(String::isNotBlank)
                .associate { entry ->
                    val parts = entry.trim().split(':')
                    require(parts.size == 2) { "booblik.topics entry must be name:partitions, got '$entry'" }
                    val count = parts[1].toIntOrNull() ?: error("partition count is not a number in '$entry'")
                    require(count > 0) { "topic ${parts[0]} needs at least one partition" }
                    TopicName(parts[0]) to count
                }

        const val DEFAULT_PORT = 9092
        private const val DEFAULT_TOPICS = "default:1"
        private const val DEFAULT_RETENTION_CHECK_MILLIS = 30_000L
        private const val DEFAULT_METRICS_INTERVAL_MILLIS = 10_000L
    }
}
