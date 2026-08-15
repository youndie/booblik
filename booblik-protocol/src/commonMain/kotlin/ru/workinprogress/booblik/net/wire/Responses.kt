package ru.workinprogress.booblik.net.wire

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName

/** What the broker answered a PRODUCE with. */
data class ProduceResult(
    val correlationId: Int,
    val error: ErrorCode,
    val baseOffset: Offset,
    val logEndOffset: Offset,
)

/** One partition, as the broker describes it. */
data class PartitionInfo(
    val partition: PartitionId,
    /** Where the **live** log starts: retention moves this, and it is not always zero. */
    val logStartOffset: Offset,
    val highWatermark: Offset,
)

/** One topic and its partitions, in the order the broker listed them. */
data class TopicInfo(
    val topic: TopicName,
    val partitions: List<PartitionInfo>,
)

/** What the broker answered a METADATA with. */
data class MetadataResult(
    val correlationId: Int,
    val error: ErrorCode,
    val topics: List<TopicInfo>,
)

/**
 * Unpacks the responses a client gets — PRODUCE, METADATA and, since M-138, FETCH.
 *
 * FETCH was deliberately absent here until then, and the reason is worth keeping: a FETCH response
 * has to be checksum-verified, because the broker streams segment bytes to the socket without
 * looking at them and the client is the only party that can; and CRC32C on the JVM is an intrinsic
 * compiling to one instruction, so a hand-written loop in common code would have cost every read
 * byte on the platform where reading actually happened. That was decision Р8's last standing
 * objection to multiplatform, and the note here said the bill would be paid when a native consumer
 * had a buyer.
 *
 * It has one. The bill turned out to be one function: [crc32c] is `expect`, the JVM keeps its
 * intrinsic, and only Kotlin/Native pays for a table.
 */
object ResponseDecoder {
    fun produce(body: ByteArray): ProduceResult {
        val reader = ByteReader(body)
        val correlationId = reader.int()
        val error = ErrorCode.of(reader.short())
        if (error != ErrorCode.NONE) {
            return ProduceResult(correlationId, error, Offset.ZERO, Offset.ZERO)
        }
        // Read into locals rather than into the constructor call: both fields are a long off the
        // same reader, and which one is which would then depend on argument evaluation order.
        val baseOffset = Offset(reader.long())
        val logEndOffset = Offset(reader.long())
        return ProduceResult(correlationId, error, baseOffset, logEndOffset)
    }

    fun metadata(body: ByteArray): MetadataResult {
        val reader = ByteReader(body)
        val correlationId = reader.int()
        val error = ErrorCode.of(reader.short())
        if (error != ErrorCode.NONE) return MetadataResult(correlationId, error, emptyList())

        val topicCount = reader.int()
        val topics = ArrayList<TopicInfo>(topicCount)
        repeat(topicCount) {
            val name = reader.bytes(reader.short().toInt() and 0xFFFF)
            val partitionCount = reader.int()
            val partitions = ArrayList<PartitionInfo>(partitionCount)
            repeat(partitionCount) {
                // Locals, in order, for the same reason `produce` uses them: three reads of the
                // same frame whose meaning is positional.
                val id = PartitionId(reader.int())
                val logStartOffset = Offset(reader.long())
                val highWatermark = Offset(reader.long())
                partitions += PartitionInfo(id, logStartOffset, highWatermark)
            }
            topics += TopicInfo(TopicName(name.decodeToString()), partitions)
        }
        return MetadataResult(correlationId, error, topics)
    }

    /**
     * Unpacks a FETCH response and verifies every record's checksum.
     *
     * [fetchOffset] is the offset that was asked for. It is here only so a failure can say *which*
     * record is damaged rather than that one of them is.
     */
    fun fetch(
        body: ByteArray,
        fetchOffset: Offset,
    ): FetchResponse {
        val reader = ByteReader(body)
        val correlationId = reader.int()
        val error = ErrorCode.of(reader.short())
        if (error != ErrorCode.NONE) {
            return FetchResponse(correlationId, error, Offset.ZERO, emptyList(), truncated = false)
        }

        val highWatermark = Offset(reader.long())
        val promised = reader.int()

        // The frame length already bounds the payload, so this field is redundant — which is exactly
        // what makes it worth checking. It is computed before the transfer starts, while the bytes
        // arrive afterwards from `transferTo` in an unpredictable number of pieces; a disagreement
        // means the two halves of the response came from different states of the log.
        check(promised == reader.remaining) {
            "booblik: FETCH promised $promised payload bytes and the frame carries ${reader.remaining}"
        }

        val records = ArrayList<ByteArray>()
        while (reader.remaining >= Protocol.RECORD_HEADER_BYTES) {
            val size = reader.int()
            val stored = reader.int()

            // A whole header is either there or not — parsing always resumes on a record boundary —
            // so a non-positive size is a malformed frame rather than a truncated tail. Empty
            // records cannot be stored at all, which is why the broker refuses them.
            check(size > 0) {
                "booblik: record header at offset ${fetchOffset + records.size.toLong()} says $size bytes"
            }
            if (size > reader.remaining) {
                return FetchResponse(
                    correlationId,
                    error,
                    highWatermark,
                    records,
                    truncated = true,
                    truncatedRecordBytes = size,
                )
            }

            val record = reader.bytes(size)
            // After the length check and never before it: a truncated tail is not corruption, and
            // reporting it as such would turn the most ordinary response there is into an alarm.
            val computed = crc32c(record)
            if (computed != stored) {
                throw CorruptRecordException(fetchOffset + records.size.toLong(), stored, computed)
            }
            records += record
        }

        // Fewer bytes left than a record header: the response stopped inside the header of the next
        // record, which is the same truncation with nothing to say about its size.
        return FetchResponse(correlationId, error, highWatermark, records, truncated = reader.remaining > 0)
    }
}

/**
 * What the broker answered a FETCH with. [records] are unframed and checksum-verified.
 *
 * Named `FetchResponse` and not `FetchResult` because `:booblik-client` still has a `FetchResult`
 * of its own, decoding the same bytes over `ByteBuffer`. Two decoders for one response is one too
 * many and is written down as M-140; until that is settled, the two names at least do not collide
 * on a classpath that has both.
 */
data class FetchResponse(
    val correlationId: Int,
    val error: ErrorCode,
    val highWatermark: Offset,
    val records: List<ByteArray>,
    /** True when the response ended inside a record, which `maxBytes` makes routine. */
    val truncated: Boolean,
    /**
     * How big the dropped record is, when its header made it into the response; zero when the
     * response stopped inside the header itself.
     *
     * The number a caller needs to tell "nothing new to read" from "the next record will never
     * fit in `maxBytes`" — the second of which never resolves itself.
     */
    val truncatedRecordBytes: Int = 0,
)

/**
 * A record whose bytes do not match the checksum stored with them.
 *
 * The client is the only party that can notice. On the zero-copy read path the broker sends segment
 * bytes to the socket without looking at them — that is what zero-copy means — so the sum is
 * computed once at write time to protect the **disk**, and verified once at read time, by whoever
 * finally holds the bytes.
 */
class CorruptRecordException(
    val offset: Offset,
    val stored: Int,
    val computed: Int,
) : IllegalStateException(
        "booblik: record at offset ${offset.value} fails its checksum: " +
            "stored $stored, computed $computed",
    )
