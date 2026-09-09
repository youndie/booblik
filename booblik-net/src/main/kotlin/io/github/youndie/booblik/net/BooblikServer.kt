package io.github.youndie.booblik.net

import io.github.youndie.booblik.PartitionId
import io.github.youndie.booblik.TopicName
import io.github.youndie.booblik.log.PartitionWriter
import io.github.youndie.booblik.net.nio.BlockingConnection
import io.github.youndie.booblik.net.nio.Connection
import io.github.youndie.booblik.net.nio.SelectorConnection
import io.github.youndie.booblik.net.nio.SelectorLoop
import io.github.youndie.booblik.storage.PartitionLog
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/** Which readiness mechanism the server runs on. Both serve the identical [Session]. */
public enum class Transport {
    /** Own selector loop, one coroutine per connection. The design decision Р3 committed to. */
    SELECTOR,

    /**
     * Blocking sockets, one virtual thread per connection. Present as the **baseline** M-36
     * measures the selector against, not as an alternative — see [BlockingConnection].
     */
    VIRTUAL_THREADS,
}

/** How a FETCH response body reaches the socket. Both paths exist so M-35 can compare them. */
public enum class FetchMode {
    /** `sendfile`: page cache to socket buffer, never through the JVM. */
    ZERO_COPY,

    /** Read into a heap buffer and write it. The control in the experiment. */
    HEAP,
}

public data class ServerConfig(
    val port: Int = 0,
    /**
     * Which address to listen on. `null` means every interface.
     *
     * This is not a convenience knob, it is a correctness one, and M-64 is the whole argument for
     * it. The JDK sets `SO_REUSEADDR` on a `ServerSocketChannel` by default, and on BSD-derived
     * systems that makes a **wildcard** bind succeed even when another process already holds the
     * same port on a specific address — after which the more specific listener receives the
     * connections and this broker receives none. It starts, it reports the port, it accepts
     * nothing, and no error appears anywhere. Binding an address explicitly turns that silent
     * theft into a bind failure at startup, which is the loud version of the same fact.
     */
    val bindAddress: String? = null,
    val transport: Transport = Transport.SELECTOR,
    val fetchMode: FetchMode = FetchMode.ZERO_COPY,
    /** Nagle off. A broker sends whole responses; batching them into segments adds latency only. */
    val tcpNoDelay: Boolean = true,
    val backlog: Int = 1024,
)

/** One partition, ready to serve: its log and the single coroutine that writes to it. */
public class PartitionHandle(
    public val log: PartitionLog,
    public val writer: PartitionWriter,
)

/**
 * Which partitions exist.
 *
 * Creating them is out of scope — there is no metadata layer and no topic creation, so a broker is
 * started with the partitions it will have. A request for anything else is answered with
 * `UNKNOWN_TOPIC_OR_PARTITION`, which is the honest code for "this broker does not have that".
 */
public class PartitionRegistry(
    private val partitions: Map<Key, PartitionHandle>,
) {
    public fun find(
        topic: TopicName,
        partition: PartitionId,
    ): PartitionHandle? = partitions[Key(topic, partition)]

    /**
     * Everything this broker has, grouped by topic and ordered.
     *
     * Ordered because it goes on the wire: an answer whose order depends on hash iteration makes
     * two identical requests look different, and the first person to diff two responses would have
     * to discover that by hand.
     */
    public fun describe(): Map<TopicName, List<Pair<PartitionId, PartitionHandle>>> =
        partitions.entries
            .sortedWith(compareBy({ it.key.topic.value }, { it.key.partition.value }))
            .groupBy({ it.key.topic }, { it.key.partition to it.value })

    public data class Key(
        val topic: TopicName,
        val partition: PartitionId,
    )

    public companion object {
        public fun of(vararg entries: Pair<Key, PartitionHandle>): PartitionRegistry =
            PartitionRegistry(entries.toMap())
    }
}

/**
 * The broker's network front end.
 *
 * Its own acceptor on a `ServerSocketChannel`, rather than Ktor's, because the read path needs the
 * real `SocketChannel` to hand to `FileChannel.transferTo` and Ktor's public API does not give one
 * out (research §1.3, decision Р3). That decision is the thing M-35 puts on trial: if zero-copy
 * turns out not to pay, this module is a lot of machinery for nothing.
 */
public class BooblikServer(
    private val partitions: PartitionRegistry,
    private val config: ServerConfig = ServerConfig(),
    public val metrics: Metrics = Metrics(),
) : Closeable {
    private val serverChannel = ServerSocketChannel.open()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + CoroutineName("booblik-server"))

    private var selectorLoop: SelectorLoop? = null
    private var acceptorThread: Thread? = null
    private var virtualThreads: java.util.concurrent.ExecutorService? = null

    /** The address actually bound. With `port = 0` this is how the caller learns the port. */
    public lateinit var address: InetSocketAddress
        private set

    public fun start(): InetSocketAddress {
        val bind =
            config.bindAddress
                ?.let { InetSocketAddress(java.net.InetAddress.getByName(it), config.port) }
                ?: InetSocketAddress(config.port)
        serverChannel.bind(bind, config.backlog)
        address = serverChannel.localAddress as InetSocketAddress

        when (config.transport) {
            Transport.SELECTOR -> startSelector()
            Transport.VIRTUAL_THREADS -> startVirtualThreads()
        }
        return address
    }

    private fun startSelector() {
        val loop = SelectorLoop()
        selectorLoop = loop
        val serverKey = loop.register(serverChannel)

        scope.launch {
            while (true) {
                // The whole body is guarded, and that is the point rather than caution. This loop
                // is the only thing that accepts connections, it is a coroutine under a
                // `SupervisorJob`, and it used to have no error handling at all — so one exception
                // ended it for good, silently, while the process stayed up and the port stayed
                // bound. A broker in that state accepts TCP connections into the backlog and
                // answers none of them, which is indistinguishable from a hung broker and is how
                // M-64 presented.
                val client =
                    try {
                        serverChannel.accept()
                    } catch (e: java.nio.channels.ClosedChannelException) {
                        // The ordinary way this loop ends: someone closed the server.
                        throw e
                    } catch (e: CancellationException) {
                        // The other ordinary way this loop ends. Counted as a transient accept
                        // failure it was not an ending at all: the loop went round again, waiting
                        // for a socket on a server that had already been told to stop.
                        throw e
                    } catch (e: Exception) {
                        // Everything else is treated as transient. Refusing one connection is
                        // recoverable; refusing every future one is not, so the loop keeps going.
                        metrics.onAcceptFailure(e)
                        loop.awaitAcceptable(serverKey)
                        continue
                    }
                if (client == null) {
                    loop.awaitAcceptable(serverKey)
                    continue
                }
                metrics.onConnectionAccepted()
                try {
                    configure(client)
                    val key = loop.register(client)
                    // Each session is its own coroutine and its own failure domain: a client that
                    // sends nonsense loses its connection and nothing else. `SupervisorJob` is what
                    // makes that true — under a plain Job the first failure would take the server
                    // down.
                    scope.launch { serve(SelectorConnection(client, key, loop)) }
                } catch (e: CancellationException) {
                    // Same reason the socket is closed below -- it is already accepted -- but a
                    // stopping server is not an accept that failed, so nothing is recorded and the
                    // cancellation goes on up.
                    @Suppress(
                        "ktlint:kapkan:cancellation-swallowed",
                        "closing an accepted socket is synchronous and the cancellation leaves below",
                    )
                    runCatching { client.close() }
                    throw e
                } catch (e: Exception) {
                    // The socket is already accepted at this point, so dropping it here would
                    // leave the client connected to nobody until a GC noticed. Close it and say so.
                    metrics.onAcceptFailure(e)
                    @Suppress(
                        "ktlint:kapkan:cancellation-swallowed",
                        "closing an accepted socket is synchronous: no suspension point inside",
                    )
                    runCatching { client.close() }
                }
            }
        }
    }

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "accept throwing is how this acceptor is told to stop; close() is the one who did it",
    )
    private fun startVirtualThreads() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        virtualThreads = executor
        // The acceptor is a platform thread: it does one blocking `accept` in a loop, so there is
        // nothing for a virtual thread to be good at here.
        acceptorThread =
            Thread({
                while (!Thread.currentThread().isInterrupted && serverChannel.isOpen) {
                    val client =
                        try {
                            serverChannel.accept()
                        } catch (_: Exception) {
                            return@Thread
                        }
                    configure(client)
                    executor.submit {
                        // `runBlocking` on a virtual thread: the session's suspension points become
                        // parks of that thread. It is what lets one session loop serve both
                        // transports.
                        runBlocking { serve(BlockingConnection(client)) }
                    }
                }
            }, "booblik-acceptor").apply {
                isDaemon = true
                start()
            }
    }

    private fun configure(client: SocketChannel) {
        client.setOption(StandardSocketOptions.TCP_NODELAY, config.tcpNoDelay)
        // A held FETCH means a connection with no traffic for up to a minute, which NAT boxes and
        // firewalls are happy to forget about. Keepalive is what makes a forgotten connection fail
        // as a failure instead of as silence on both sides.
        client.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
    }

    private suspend fun serve(connection: Connection) {
        metrics.onConnectionOpened()
        try {
            Session(connection, partitions, config.fetchMode, metrics).serve()
        } catch (e: CancellationException) {
            // The server stopping is not a session that died holding a request. Recorded as one it
            // was a burst of session failures at every shutdown, one per live connection.
            @Suppress(
                "ktlint:kapkan:cancellation-swallowed",
                "closing the connection is synchronous and the cancellation leaves on the next line",
            )
            runCatching { connection.close() }
            throw e
        } catch (e: Exception) {
            // Reaching here means the session died **holding a request**: a client that simply
            // leaves between frames returns through `Session.serve` without an exception. From the
            // client's side this is a connection that dropped instead of answering, so it is worth
            // recording — and it used to be discarded, which left an intermittent failure in
            // `ServerTest` with no evidence attached to it at all (M-64). There is still nobody to
            // tell over the wire: the connection is what broke.
            metrics.onSessionFailure(e)
            @Suppress(
                "ktlint:kapkan:cancellation-swallowed",
                "closing the connection is synchronous: there is no suspension point inside",
            )
            runCatching { connection.close() }
        } finally {
            metrics.onConnectionClosed()
        }
    }

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "a listener that will not close cannot change a shutdown that is already under way",
    )
    override fun close() {
        runCatching { serverChannel.close() }
        acceptorThread?.interrupt()
        scope.cancel()
        virtualThreads?.shutdownNow()
        selectorLoop?.close()
        job.cancel()
    }
}
