package ru.workinprogress.booblik.net

import ru.workinprogress.booblik.Offset
import ru.workinprogress.booblik.net.wire.ApiKey
import ru.workinprogress.booblik.net.wire.ErrorCode
import ru.workinprogress.booblik.net.wire.Protocol
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens when a frame does not arrive all at once, or does not make sense.
 *
 * These go through a raw socket rather than [ru.workinprogress.booblik.net.client.BooblikClient],
 * because the client is well-behaved by construction and the interesting cases are the ones it
 * cannot produce.
 *
 * The delay in the first test is the point of the whole file: on loopback a request is normally
 * already in the receive buffer by the time the session looks, so `read` never returns zero and the
 * suspend-and-wait path in the selector is never taken. Splitting the frame forces it. Without a
 * test like this the selector's readiness handling is exercised only under load, which is where it
 * is least pleasant to find out about.
 */
class PartialFrameTest {
    private fun withRawSocket(body: (BooblikServer, SocketChannel) -> Unit) {
        withServer { server, _ ->
            SocketChannel.open(InetSocketAddress("127.0.0.1", server.address.port)).use { socket ->
                socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
                body(server, socket)
            }
        }
    }

    @Test
    fun `a frame split across two packets is served once the rest arrives`() {
        for (transport in Transport.entries) {
            withServer(transport) { server, _ ->
                SocketChannel.open(InetSocketAddress("127.0.0.1", server.address.port)).use { socket ->
                    socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
                    val frame = fetchFrame(correlationId = 42)

                    // The length prefix alone, then a pause long enough that the session has
                    // certainly reached the point of waiting for more.
                    val head = frame.duplicate().limit(Protocol.LENGTH_PREFIX_BYTES) as ByteBuffer
                    writeFully(socket, head)
                    Thread.sleep(150)

                    val tail = frame.duplicate().position(Protocol.LENGTH_PREFIX_BYTES) as ByteBuffer
                    writeFully(socket, tail)

                    val response = readFrame(socket)
                    assertEquals(42, response.int, "transport=$transport")
                    assertEquals(ErrorCode.NONE, ErrorCode.of(response.short), "transport=$transport")
                }
            }
        }
    }

    @Test
    fun `a byte at a time still assembles into one request`() {
        withRawSocket { _, socket ->
            val frame = fetchFrame(correlationId = 7)
            while (frame.hasRemaining()) {
                val single = ByteBuffer.allocate(1).put(frame.get()).flip()
                writeFully(socket, single)
            }
            val response = readFrame(socket)
            assertEquals(7, response.int)
            assertEquals(ErrorCode.NONE, ErrorCode.of(response.short))
        }
    }

    @Test
    fun `an unserviceable frame is answered, and the connection survives it`() {
        withRawSocket { _, socket ->
            // Valid framing and a valid header, asking for an api key that does not exist. The
            // broker can name the problem *and* echo the correlation id, because the part of the
            // frame carrying it parsed fine. Codes are covered in ErrorCodeTest; what matters here
            // is that the connection is not dropped over it.
            val body =
                ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES).apply {
                    putShort(999)
                    putShort(Protocol.VERSION)
                    putInt(5)
                    flip()
                }
            val frame =
                ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + body.remaining()).apply {
                    putInt(body.remaining())
                    put(body)
                    flip()
                }
            writeFully(socket, frame)

            val response = readFrame(socket)
            assertEquals(5, response.int, "the header parsed, so the id must come back")
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, ErrorCode.of(response.short))

            // And the connection is still usable.
            writeFully(socket, fetchFrame(correlationId = 8))
            val second = readFrame(socket)
            assertEquals(8, second.int)
        }
    }

    @Test
    fun `an absurd frame length costs the connection, not the broker`() {
        withRawSocket { _, socket ->
            val header = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES).putInt(Int.MAX_VALUE).flip()
            writeFully(socket, header)

            // The broker must not try to allocate two gigabytes on a 64 MiB heap. It drops the
            // connection instead, which is what the client sees.
            val closed =
                runCatching {
                    val buffer = ByteBuffer.allocate(16)
                    while (true) {
                        if (socket.read(buffer) < 0) throw EOFException()
                        if (!buffer.hasRemaining()) break
                    }
                }.exceptionOrNull()
            assertTrue(closed is EOFException || closed is java.io.IOException, "got $closed")
        }
    }

    private fun fetchFrame(correlationId: Int): ByteBuffer {
        val topic = TOPIC.value.toByteArray()
        val bodyBytes =
            Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES + topic.size + Int.SIZE_BYTES +
                Long.SIZE_BYTES + Int.SIZE_BYTES
        return ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + bodyBytes).apply {
            putInt(bodyBytes)
            putShort(ApiKey.FETCH.id)
            putShort(Protocol.VERSION)
            putInt(correlationId)
            putShort(topic.size.toShort())
            put(topic)
            putInt(PARTITION.value)
            putLong(Offset.ZERO.value)
            putInt(1024)
            flip()
        }
    }

    private fun writeFully(
        socket: SocketChannel,
        buffer: ByteBuffer,
    ) {
        while (buffer.hasRemaining()) socket.write(buffer)
    }

    private fun readFrame(socket: SocketChannel): ByteBuffer {
        val prefix = ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES)
        readFully(socket, prefix)
        prefix.flip()
        val body = ByteBuffer.allocate(prefix.int)
        readFully(socket, body)
        body.flip()
        return body
    }

    private fun readFully(
        socket: SocketChannel,
        buffer: ByteBuffer,
    ) {
        while (buffer.hasRemaining()) {
            if (socket.read(buffer) < 0) throw EOFException("broker closed the connection")
        }
    }
}
