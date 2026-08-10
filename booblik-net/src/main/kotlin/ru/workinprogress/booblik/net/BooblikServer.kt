package ru.workinprogress.booblik.net

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.workinprogress.booblik.PartitionId
import ru.workinprogress.booblik.TopicName
import ru.workinprogress.booblik.log.PartitionWriter
import ru.workinprogress.booblik.net.nio.BlockingConnection
import ru.workinprogress.booblik.net.nio.Connection
import ru.workinprogress.booblik.net.nio.SelectorConnection
import ru.workinprogress.booblik.net.nio.SelectorLoop
import ru.workinprogress.booblik.storage.PartitionLog
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors

/** Which readiness mechanism the server runs on. Both serve the identical [Session]. */
enum class Transport {
    /** Own selector loop, one coroutine per connection. The design decision Р3 committed to. */
    SELECTOR,

    /**
     * Blocking sockets, one virtual thread per connection. Present as the **baseline** M-36
     * measures the selector against, not as an alternative — see [BlockingConnection].
     */
    VIRTUAL_THREADS,
}

/** How a FETCH response body reaches the socket. Both paths exist so M-35 can compare them. */
enum class FetchMode {
    /** `sendfile`: page cache to socket buffer, never through the JVM. */
    ZERO_COPY,

    /** Read into a heap buffer and write it. The control in the experiment. */
    HEAP,
}

data class ServerConfig(
    val port: Int = 0,
    val transport: Transport = Transport.SELECTOR,
    val fetchMode: FetchMode = FetchMode.ZERO_COPY,
    /** Nagle off. A broker sends whole responses; batching them into segments adds latency only. */
    val tcpNoDelay: Boolean = true,
    val backlog: Int = 1024,
)

/** One partition, ready to serve: its log and the single coroutine that writes to it. */
class PartitionHandle(
    val log: PartitionLog,
    val writer: PartitionWriter,
)

/**
 * Which partitions exist.
 *
 * Creating them is out of scope — there is no metadata layer and no topic creation, so a broker is
 * started with the partitions it will have. A request for anything else is answered with
 * `UNKNOWN_TOPIC_OR_PARTITION`, which is the honest code for "this broker does not have that".
 */
class PartitionRegistry(
    private val partitions: Map<Key, PartitionHandle>,
) {
    fun find(
        topic: TopicName,
        partition: PartitionId,
    ): PartitionHandle? = partitions[Key(topic, partition)]

    data class Key(
        val topic: TopicName,
        val partition: PartitionId,
    )

    companion object {
        fun of(vararg entries: Pair<Key, PartitionHandle>) = PartitionRegistry(entries.toMap())
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
class BooblikServer(
    private val partitions: PartitionRegistry,
    private val config: ServerConfig = ServerConfig(),
) : Closeable {
    private val serverChannel = ServerSocketChannel.open()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + CoroutineName("booblik-server"))

    private var selectorLoop: SelectorLoop? = null
    private var acceptorThread: Thread? = null
    private var virtualThreads: java.util.concurrent.ExecutorService? = null

    /** The address actually bound. With `port = 0` this is how the caller learns the port. */
    lateinit var address: InetSocketAddress
        private set

    fun start(): InetSocketAddress {
        serverChannel.bind(InetSocketAddress(config.port), config.backlog)
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
                val client = serverChannel.accept()
                if (client == null) {
                    loop.awaitAcceptable(serverKey)
                    continue
                }
                configure(client)
                val key = loop.register(client)
                // Each session is its own coroutine and its own failure domain: a client that
                // sends nonsense loses its connection and nothing else. `SupervisorJob` is what
                // makes that true — under a plain Job the first failure would take the server down.
                scope.launch { serve(SelectorConnection(client, key, loop)) }
            }
        }
    }

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
    }

    private suspend fun serve(connection: Connection) {
        try {
            Session(connection, partitions, config.fetchMode).serve()
        } catch (_: Exception) {
            // A broken connection is the ordinary end of a session, not an event. Anything worth
            // reporting is reported through an error code while the connection is still alive;
            // by the time an exception gets here, there is nobody left to tell.
            runCatching { connection.close() }
        }
    }

    override fun close() {
        runCatching { serverChannel.close() }
        acceptorThread?.interrupt()
        scope.cancel()
        virtualThreads?.shutdownNow()
        selectorLoop?.close()
        job.cancel()
    }
}
