package ru.workinprogress.booblik.native.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.native.BooblikConnection
import ru.workinprogress.booblik.native.Consumer
import ru.workinprogress.booblik.net.wire.ErrorCode
import kotlin.system.exitProcess

/**
 * This client under test, driven by `conformance/harness`.
 *
 * The contract is in `conformance/README.md`: verbs on argv, `key=value` on stdout, the broker in
 * `BOOBLIK_BROKER`. Exit 0 means the verb was carried out — **including** when the broker refused
 * it, which is a result and is reported as `error=CODE`. A non-zero exit means this program failed.
 *
 * Declares `producer,consumer` since M-138, when the FETCH decoder moved into `:booblik-protocol`
 * behind an `expect` checksum: the JVM keeps its intrinsic and Kotlin/Native pays for a table, which
 * turned out to be the whole of decision Р8's last standing objection.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: conformance <verb> [args...]  (broker in BOOBLIK_BROKER)")
        exitProcess(2)
    }

    if (args[0] == "capabilities") {
        println("roles=producer,consumer")
        println("name=kotlin-native")
        return
    }

    val address =
        getenv("BOOBLIK_BROKER")?.toKString()
            ?: run {
                println("BOOBLIK_BROKER is not set (host:port)")
                exitProcess(2)
            }

    BooblikConnection(address).use { connection ->
        when (val verb = args[0]) {
            "metadata" -> {
                metadata(connection, args[1])
            }

            "produce" -> {
                produce(connection, args[1], args[2].toInt(), args[3], args[4])
            }

            "produce-keyed" -> {
                produceKeyed(connection, args[1], hex(args[2]), hex(args[3]))
            }

            "fetch" -> {
                fetch(connection, args[1], args[2].toInt(), args[3].toLong(), args[4].toInt())
            }

            else -> {
                println("unknown verb: $verb")
                exitProcess(2)
            }
        }
    }
}

private fun metadata(
    connection: BooblikConnection,
    topic: String,
) {
    val answer = connection.metadata(listOf(TopicName(topic)))
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    for (info in answer.topics) {
        for (partition in info.partitions) {
            println(
                "partition=${partition.partition.value} " +
                    "${partition.logStartOffset.value} ${partition.highWatermark.value}",
            )
        }
    }
}

private fun produce(
    connection: BooblikConnection,
    topic: String,
    partition: Int,
    ack: String,
    records: String,
) {
    val policy =
        when (ack) {
            "none" -> AckPolicy.NONE
            "written" -> AckPolicy.WRITTEN
            "forced" -> AckPolicy.FORCED
            else -> error("unknown ack policy: $ack")
        }

    // An empty field is a zero-length record and it is passed on rather than tidied away: the broker
    // refuses those, and the harness checks that the refusal reaches the caller.
    val payloads = records.split(",").map { hex(it) }

    // Null under AckPolicy.NONE, and printing nothing is the correct answer: no offset exists yet.
    // Reading for one here is what the harness times out on.
    val answer = connection.produce(TopicName(topic), PartitionId(partition), payloads, policy) ?: return
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    println("baseOffset=${answer.baseOffset.value}")
    println("logEndOffset=${answer.logEndOffset.value}")
}

/**
 * Where the partitioner is exercised for real: the partition is chosen here, from the key, because
 * the broker never sees the key at all.
 */
private fun produceKeyed(
    connection: BooblikConnection,
    name: String,
    key: ByteArray,
    payload: ByteArray,
) {
    val topic = connection.topic(TopicName(name))
    val chosen = topic.partitionFor(key)

    val answer = connection.produce(topic.name, chosen, listOf(payload))!!
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    println("partition=${chosen.value}")
    println("baseOffset=${answer.baseOffset.value}")
}

/**
 * The raw call and not a [Consumer]: the checks are about what one FETCH answers — a truncated tail,
 * an empty response at the high watermark, a refusal past the end — and a Consumer would smooth over
 * exactly those by advancing a position and waiting.
 */
private fun fetch(
    connection: BooblikConnection,
    topic: String,
    partition: Int,
    offset: Long,
    maxBytes: Int,
) {
    val answer = connection.fetch(TopicName(topic), PartitionId(partition), Offset(offset), maxBytes)
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    println("highWatermark=${answer.highWatermark.value}")

    // Nothing whole and something partial: the next record is bigger than maxBytes and never
    // arrives, so a caller that only sees an empty list cannot tell this from having caught up.
    if (answer.records.isEmpty() && answer.truncated) {
        println("recordExceedsMaxBytes=${answer.truncatedRecordBytes}")
        return
    }

    for (record in answer.records) {
        println("record=${record.joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }}")
    }
}

private fun report(error: ErrorCode) {
    println("error=$error")
}

private fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
