package ru.workinprogress.booblik.native.conformance

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.native.BooblikConnection
import ru.workinprogress.booblik.native.Producer
import ru.workinprogress.booblik.native.ProducerConfig
import kotlin.math.round
import kotlin.system.exitProcess
import kotlin.time.TimeSource

/**
 * What the accumulator is worth on Kotlin/Native, against a real broker.
 *
 * M-134а rests on a claim rather than a fact: that a linger window pays off with **concurrent**
 * callers and buys nothing for a single one, because the lone caller is inside the accumulator
 * instead of producing. That is the shape of the group-commit finding in M-120, where the window
 * lost everywhere for exactly this reason — so it is worth measuring rather than repeating.
 *
 * Two columns, differing **only** in the accumulator:
 *
 *  * **direct** — N callers, one connection and one thread each, every record its own request
 *  * **batched** — the same N callers on the same N threads, all handing to one accumulator over one
 *    connection
 *
 * A thread per caller in both columns, and that is not incidental: `runBlocking` gives one thread,
 * the connection is blocking, and sixty-four callers on one thread would queue rather than overlap.
 * A comparison run that way measures a queue.
 *
 * It also checks what it produced: every record must come back with an offset and the offsets must
 * be distinct. A number from a run that lost records is worse than no number — that is exactly how
 * the JVM accumulator looked fine twice while dropping one.
 *
 * Usage:  BOOBLIK_BROKER=host:port probe [recordsPerCaller]
 */
@OptIn(ExperimentalForeignApi::class)
fun probe(args: Array<String>) {
    val address =
        getenv("BOOBLIK_BROKER")?.toKString() ?: run {
            println("BOOBLIK_BROKER is not set (host:port)")
            exitProcess(2)
        }
    val perCaller = args.firstOrNull()?.toIntOrNull() ?: 200
    val topic = TopicName("probe")
    val partition = PartitionId(0)

    println("→ accumulator on Kotlin/Native — $perCaller records per caller, topic ${topic.value}")
    println()
    println("   callers   direct rec/s   batched rec/s   batched, not awaited   ratio")

    for (callers in listOf(1, 8, 64)) {
        val direct = measure(address, topic, partition, callers, perCaller, Mode.DIRECT)
        val batched = measure(address, topic, partition, callers, perCaller, Mode.BATCHED)
        val pipelined = measure(address, topic, partition, callers, perCaller, Mode.BATCHED_NOT_AWAITED)

        println(
            "   ${callers.toString().padStart(7)}   ${thousands(direct).padStart(12)}   " +
                "${thousands(batched).padStart(13)}   ${thousands(pipelined).padStart(20)}   " +
                "${round(pipelined / direct * 100) / 100}×",
        )
    }
}

private enum class Mode {
    /** Every record its own request, awaited before the next. What a caller without one does. */
    DIRECT,

    /** Through the accumulator, still awaited before the next — a request handler's pattern. */
    BATCHED,

    /**
     * Through the accumulator, awaited at the end.
     *
     * The case the accumulator exists for, and the one the blocking connection cannot offer at all:
     * without it there is no way to have more than one record in flight, so there is no `direct`
     * column to put beside this one.
     */
    BATCHED_NOT_AWAITED,
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private fun measure(
    address: String,
    topic: TopicName,
    partition: PartitionId,
    callers: Int,
    perCaller: Int,
    mode: Mode,
): Double {
    val accumulated = mode != Mode.DIRECT
    // One thread per caller in both columns, so the only difference between them is the
    // accumulator rather than how much overlap the dispatcher allowed.
    val threads: List<CloseableCoroutineDispatcher> =
        List(callers) { newSingleThreadContext("probe-caller-$it") }

    // Batched: one connection behind one accumulator. Direct: a connection each, which is what a
    // caller without an accumulator has to do — they cannot share one, responses being matched in
    // the order the requests went out.
    val connections =
        if (accumulated) {
            listOf(BooblikConnection(address))
        } else {
            List(callers) { BooblikConnection(address) }
        }
    val scope = CoroutineScope(SupervisorJob())
    val producer =
        if (accumulated) {
            Producer(connections[0], scope, ProducerConfig(maxBatchSize = 100, lingerMillis = 5))
        } else {
            null
        }

    try {
        return runBlocking {
            val started = TimeSource.Monotonic.markNow()

            // Each caller sends one record and waits for its offset before sending the next, in
            // both columns. That is what a request handler does, and it is the only comparison
            // worth drawing: firing everything and awaiting at the end would let the batched column
            // pipeline while the direct one round-trips, and the number would be about pipelining.
            val offsets =
                (0 until callers)
                    .map { caller ->
                        async(threads[caller]) {
                            if (producer != null && mode == Mode.BATCHED_NOT_AWAITED) {
                                // Queue everything, then collect. Records pile up, so a batch can
                                // hold more than there are callers — which is the only way a linger
                                // window ever fills.
                                List(perCaller) { index ->
                                    producer.send(topic, partition, payload(caller, index))
                                }.map { it.await()!! }
                            } else if (producer != null) {
                                List(perCaller) { index ->
                                    producer.send(topic, partition, payload(caller, index)).await()!!
                                }
                            } else {
                                List(perCaller) { index ->
                                    connections[caller]
                                        .produce(topic, partition, listOf(payload(caller, index)))!!
                                        .baseOffset
                                }
                            }
                        }
                    }.awaitAll()
                    .flatten()

            val elapsed = started.elapsedNow()
            verify(offsets, callers * perCaller)
            offsets.size / elapsed.inWholeMicroseconds.toDouble() * 1_000_000
        }
    } finally {
        producer?.close()
        scope.cancel()
        connections.forEach { it.close() }
        threads.forEach { it.close() }
    }
}

private fun verify(
    offsets: List<Offset>,
    expected: Int,
) {
    if (offsets.size != expected) {
        println("   ✗ $expected records went in and ${offsets.size} came back")
        exitProcess(1)
    }
    if (offsets.map { it.value }.toSet().size != expected) {
        println("   ✗ two records were given the same offset")
        exitProcess(1)
    }
}

private fun payload(
    caller: Int,
    index: Int,
): ByteArray = "probe-$caller-$index".encodeToByteArray()

private fun thousands(perSecond: Double): String =
    round(perSecond)
        .toLong()
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
