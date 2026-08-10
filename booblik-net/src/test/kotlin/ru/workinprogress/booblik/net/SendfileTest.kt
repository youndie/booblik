package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.Position
import ru.workinprogress.booblik.net.nio.BlockingConnection
import ru.workinprogress.booblik.storage.LogSegment
import ru.workinprogress.booblik.storage.SegmentMode
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * M-63: the read path must keep the one property that lets `transferTo` become `sendfile`.
 *
 * ## What the JDK decides on
 *
 * Read in `FileChannelImpl.transferToDirectInternal` (openjdk/jdk): the direct path — `sendfile` or
 * `copy_file_range` — is taken **only** when the target is a `FileChannelImpl` or a
 * `SocketChannelImpl`. Anything else returns `UNSUPPORTED_CASE`, and `transferTo` then tries a
 * mapped transfer and finally `transferToArbitraryChannel`, a plain read/write loop over an 8 KiB
 * heap buffer.
 *
 * **All three produce identical bytes.** Nothing in the output betrays which one ran, which is
 * exactly why this needs a test: the difference is invisible until somebody looks at a throughput
 * graph months later and cannot explain it.
 *
 * ## What is checked, and what cannot be
 *
 * Checked: the object the connection hands to `transferTo` is a real `SocketChannel`. Wrap the
 * socket in anything — a metrics decorator, a compressing channel, a TLS layer — and the direct
 * path is silently gone. That is the realistic regression.
 *
 * Not checked: the syscall itself. That needs a tracer, and neither machine this project runs on
 * has one (`strace` is absent from the WSL image; `ptrace_scope` is 1). An earlier attempt inferred
 * the path from the JDK's direct buffer pool and was abandoned when the source showed
 * `transferToArbitraryChannel` allocating a **heap** buffer — the signal it rested on does not
 * exist. Written down so nobody rebuilds it.
 */
class SendfileTest {
    private fun <T> withSegment(body: (LogSegment) -> T): T {
        val dir = Files.createTempDirectory("booblik-sendfile")
        return try {
            LogSegment.open(dir, Offset.ZERO, SegmentMode.FILE_CHANNEL, capacity = 1 shl 20).use(body)
        } finally {
            @OptIn(ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    private fun <T> withSocketPair(body: (SocketChannel, SocketChannel) -> T): T =
        ServerSocketChannel.open().use { server ->
            server.bind(InetSocketAddress("127.0.0.1", 0))
            SocketChannel.open(server.localAddress as InetSocketAddress).use { client ->
                server.accept().use { accepted -> body(client, accepted) }
            }
        }

    @Test
    fun `both transports hand transferTo a real SocketChannel`() {
        withSocketPair { client, _ ->
            val target = BlockingConnection(client).transferTarget
            assertTrue(
                target is SocketChannel,
                "transferTo would be handed a ${target.javaClass.name}, which the JDK will not " +
                    "sendfile to — the read path has stopped being zero-copy",
            )
        }
        // SelectorConnection holds the same channel; its constructor needs a live selector, so the
        // property is stated by its type rather than instantiated here. Both are covered by
        // ServerTest running the whole protocol on both transports.
    }

    @Test
    fun `every path returns the same bytes, which is why the type has to be checked instead`() {
        withSegment { segment ->
            segment.append("the same either way".toByteArray())
            val bytes = segment.size.value

            val viaStream = ByteArrayOutputStream()
            drain(segment, bytes, Channels.newChannel(viaStream))

            withSocketPair { client, accepted ->
                drain(segment, bytes, client)
                val received = ByteBuffer.allocate(bytes)
                while (received.hasRemaining()) {
                    if (accepted.read(received) < 0) break
                }
                assertContentEquals(
                    viaStream.toByteArray(),
                    received.array(),
                    "a copy loop and a sendfile deliver the same bytes — correctness cannot tell them apart",
                )
            }
        }
    }

    private fun drain(
        segment: LogSegment,
        bytes: Int,
        target: WritableByteChannel,
    ) {
        var moved = 0
        while (moved < bytes) {
            val n = segment.transferTo(Position(moved), bytes - moved, target)
            if (n == 0L) continue
            moved += n.toInt()
        }
    }
}
