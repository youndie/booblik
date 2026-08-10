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

    val server = BooblikServer(registry, ServerConfig(port = 0, transport = transport, fetchMode = fetchMode))
    try {
        val address = server.start()
        BooblikClient(address).use { client -> body(server, client) }
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
