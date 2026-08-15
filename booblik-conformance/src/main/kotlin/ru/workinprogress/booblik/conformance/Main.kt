package ru.workinprogress.booblik.conformance

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.AckPolicy
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.net.client.Partitioner
import ru.workinprogress.booblik.net.wire.ErrorCode
import java.net.InetSocketAddress
import kotlin.system.exitProcess

/**
 * The reference implementation of the conformance client contract.
 *
 * It exists to prove the harness works. A harness with no client is a program nobody has run, and
 * this project has its own name for that failure — a thing that is written and never called. Every
 * check in `conformance/harness/scenarios.py` is green against this client before any check is
 * asked of anybody else's.
 *
 * It is also the worked example the contract is documented by: a client author in another language
 * reads `conformance/README.md` for the shape and this file for what a real answer looks like.
 *
 * ## What green here does **not** prove
 *
 * This client picks partitions with the very [Partitioner.Fnv1a] it is meant to be checked against,
 * so `produce-keyed` passing says the harness compares the right things — not that the Kotlin
 * partitioner is correct. That claim belongs to `ConformanceVectorsTest`, which holds it against
 * vectors computed in another language. Conflating the two would leave the JVM client the one
 * client checked only by itself.
 *
 * Built on [BooblikClient], the low-level blocking client, rather than on `Producer`: this needs
 * exact control over what is sent and whether anything is read back — `ack=none` is checked
 * precisely by the absence of a read — and an accumulator between the contract and the socket would
 * make the answers describe the accumulator.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: booblik-conformance <verb> [args...]  (broker in BOOBLIK_BROKER)")
        exitProcess(2)
    }

    if (args[0] == "capabilities") {
        println("roles=producer,consumer")
        println("name=kotlin (reference)")
        return
    }

    val broker =
        System.getenv("BOOBLIK_BROKER") ?: run {
            System.err.println("BOOBLIK_BROKER is not set (host:port)")
            exitProcess(2)
        }
    val address = InetSocketAddress(broker.substringBefore(':'), broker.substringAfter(':').toInt())

    try {
        BooblikClient(address).use { client ->
            when (val verb = args[0]) {
                "metadata" -> {
                    metadata(client, args[1])
                }

                "produce" -> {
                    produce(client, args[1], args[2].toInt(), args[3], records(args[4]))
                }

                "produce-keyed" -> {
                    produceKeyed(client, args[1], hex(args[2]), hex(args[3]))
                }

                "fetch" -> {
                    fetch(client, args[1], args[2].toInt(), args[3].toLong(), args[4].toInt())
                }

                else -> {
                    System.err.println("unknown verb: $verb")
                    exitProcess(2)
                }
            }
        }
    } catch (failure: Exception) {
        // Anything that is not a protocol error is this client failing, and the harness is right to
        // treat a non-zero exit as exactly that. A broker refusal is not a failure and never
        // reaches here — it is reported as `error=` with a zero exit.
        System.err.println("${failure::class.simpleName}: ${failure.message}")
        exitProcess(1)
    }
}

private fun metadata(
    client: BooblikClient,
    topic: String,
) {
    client.sendMetadata(listOf(TopicName(topic)))
    val answer = client.receiveMetadata()
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
    client: BooblikClient,
    topic: String,
    partition: Int,
    ack: String,
    records: List<ByteArray>,
) {
    val policy =
        when (ack) {
            "none" -> AckPolicy.NONE
            "written" -> AckPolicy.WRITTEN
            "forced" -> AckPolicy.FORCED
            else -> error("unknown ack policy: $ack")
        }

    client.sendProduce(TopicName(topic), PartitionId(partition), records, policy)
        // Null means AckPolicy.NONE: no response is coming, so reading one would block for ever.
        // Returning here rather than reading is the behaviour the harness times.
        ?: return

    val answer = client.receiveProduce()
    if (answer.error != ErrorCode.NONE) return report(answer.error)
    println("baseOffset=${answer.baseOffset.value}")
    println("logEndOffset=${answer.logEndOffset.value}")
}

/**
 * Picks the partition from the key and then produces to it, which is the order that matters:
 * the broker never sees the key, so the choice is made here or nowhere.
 *
 * Partitions come from METADATA rather than from a count passed in, and are indexed in the order
 * the broker reports them — which the protocol guarantees is by partition id (§4а). Choosing
 * against a hand-supplied count is how a client ends up piling records into the partitions that
 * exist while never writing to the others.
 */
private fun produceKeyed(
    client: BooblikClient,
    topic: String,
    key: ByteArray,
    payload: ByteArray,
) {
    client.sendMetadata(listOf(TopicName(topic)))
    val metadata = client.receiveMetadata()
    if (metadata.error != ErrorCode.NONE) return report(metadata.error)

    val partitions =
        metadata.topics
            .single()
            .partitions
            .map { it.partition }
    val chosen = partitions[Partitioner.Fnv1a.partitionFor(key, partitions.size)]

    client.sendProduce(TopicName(topic), chosen, listOf(payload), AckPolicy.WRITTEN)
    val answer = client.receiveProduce()
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    println("partition=${chosen.value}")
    println("baseOffset=${answer.baseOffset.value}")
}

private fun fetch(
    client: BooblikClient,
    topic: String,
    partition: Int,
    offset: Long,
    maxBytes: Int,
) {
    client.sendFetch(TopicName(topic), PartitionId(partition), Offset(offset), maxBytes)
    val answer = client.receiveFetch()
    if (answer.error != ErrorCode.NONE) return report(answer.error)

    println("highWatermark=${answer.highWatermark.value}")
    // Nothing whole and something partial: the next record is bigger than `maxBytes` and never
    // arrives, so a caller that only sees an empty list cannot tell this from having caught up.
    if (answer.records.isEmpty() && answer.truncated) {
        println("recordExceedsMaxBytes=${answer.truncatedRecordBytes}")
        return
    }
    // `records` already excludes an incomplete trailing record — `maxBytes` bounds the response in
    // bytes, not in records, and returning the fragment would hand the caller half a record.
    for (record in answer.records) println("record=${hex(record)}")
}

private fun report(error: ErrorCode) {
    println("error=$error")
}

/**
 * Comma-separated hex records. An empty field is a zero-length record, and passing one on is the
 * point: the broker refuses those with `CORRUPT_REQUEST`, and the harness checks that the refusal
 * reaches the caller rather than being tidied away here.
 */
private fun records(argument: String): List<ByteArray> = argument.split(",").map { hex(it) }

private fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
