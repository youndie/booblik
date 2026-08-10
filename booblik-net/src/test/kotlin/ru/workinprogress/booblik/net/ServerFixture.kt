package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.client.BooblikClient
import ru.workinprogress.booblik.storage.PartitionLog
import ru.workinprogress.booblik.storage.SegmentMode
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

val TOPIC = TopicName("orders")
val PARTITION = PartitionId(0)

/**
 * Starts a broker with one partition, runs [body] against it, and takes everything down.
 *
 * Port zero, so tests never collide with each other or with whatever else is on the machine — the
 * actual port comes back from `start()`.
 */
fun withServer(
    transport: Transport = Transport.SELECTOR,
    fetchMode: FetchMode = FetchMode.ZERO_COPY,
    segmentCapacity: Int = 1 shl 20,
    body: (BooblikServer, BooblikClient) -> Unit,
) {
    val dir = Files.createTempDirectory("booblik-net")
    val scope = CoroutineScope(SupervisorJob())
    val log = PartitionLog.open(dir, SegmentMode.FILE_CHANNEL, segmentCapacity)
    val writer = PartitionWriter(log, scope)
    val registry =
        PartitionRegistry.of(
            PartitionRegistry.Key(TOPIC, PARTITION) to PartitionHandle(log, writer),
        )

    // Loopback explicitly, not the wildcard, and this line is the fix for M-64. With a wildcard
    // bind the OS is free to hand out a port that another process on the machine already holds on
    // 127.0.0.1 — `SO_REUSEADDR` is on by default, so the bind succeeds — and every connection then
    // goes to that other process instead of to this server. It presented as a client seeing EOF
    // from a broker that had accepted nothing, roughly once in fifteen thousand connections, and
    // took a day to pin on a stray `kubectl port-forward`. Binding the address the client will
    // actually dial makes the collision a startup failure instead of a mystery.
    val server =
        BooblikServer(
            registry,
            ServerConfig(port = 0, transport = transport, fetchMode = fetchMode, bindAddress = "127.0.0.1"),
        )
    try {
        val address = server.start()
        try {
            BooblikClient(address).use { client -> body(server, client) }
        } catch (e: Throwable) {
            // Half of the evidence for a network failure is on the other side of the socket, and it
            // is gone the moment this fixture tears the server down. A client that sees "broker
            // closed the connection" says nothing about *why*; the session that closed it usually
            // does. Chasing M-64 without this meant guessing.
            server.metrics.lastSessionFailure?.let { e.addSuppressed(it) }
            throw e
        }
    } finally {
        server.close()
        scope.cancel()
        log.close()
        @OptIn(ExperimentalPathApi::class)
        dir.deleteRecursively()
    }
}

/** Both transports and both fetch paths, since the point of having four is that they agree. */
fun eachConfiguration(body: (Transport, FetchMode) -> Unit) {
    for (transport in Transport.entries) {
        for (fetchMode in FetchMode.entries) {
            body(transport, fetchMode)
        }
    }
}
