package ru.workinprogress.booblik.net

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

/**
 * M-43: every error the broker can answer with, and — just as important — the correlation id it
 * answers with.
 *
 * A client that pipelines matches answers to requests by that id. An error carrying the wrong one
 * does not merely lose information: it resolves somebody else's request.
 */
class ErrorCodeTest {
    private fun withRawSocket(body: (SocketChannel) -> Unit) {
        withServer { server, _ ->
            SocketChannel.open(InetSocketAddress("127.0.0.1", server.address.port)).use { socket ->
                socket.setOption(StandardSocketOptions.TCP_NODELAY, true)
                body(socket)
            }
        }
    }

    @Test
    fun `an unknown api key is UNSUPPORTED_VERSION, and keeps the correlation id`() {
        // Not CORRUPT_REQUEST: the frame is perfectly well formed, the broker simply does not
        // implement what it asks for — and it is well formed enough to answer by name.
        withRawSocket { socket ->
            writeHeaderOnly(socket, apiKey = 42, version = Protocol.VERSION, correlationId = 77)
            val response = readFrame(socket)
            assertEquals(77, response.int)
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, ErrorCode.of(response.short))
        }
    }

    @Test
    fun `an unknown api version is UNSUPPORTED_VERSION, and keeps the correlation id`() {
        withRawSocket { socket ->
            writeHeaderOnly(socket, apiKey = ApiKey.FETCH.id, version = 99, correlationId = 12)
            val response = readFrame(socket)
            assertEquals(12, response.int)
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, ErrorCode.of(response.short))
        }
    }

    @Test
    fun `a frame too short to hold a header answers with zero`() {
        // There is no id to echo, and inventing one would resolve a request at random.
        withRawSocket { socket ->
            val body =
                ByteBuffer
                    .allocate(3)
                    .putShort(1)
                    .put(0)
                    .flip()
            val frame =
                ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + body.remaining()).apply {
                    putInt(body.remaining())
                    put(body)
                    flip()
                }
            writeFully(socket, frame)

            val response = readFrame(socket)
            assertEquals(0, response.int)
            assertEquals(ErrorCode.CORRUPT_REQUEST, ErrorCode.of(response.short))
        }
    }

    @Test
    fun `a well-formed header with a broken body keeps the correlation id`() {
        withRawSocket { socket ->
            // Valid header, then a topic name length that runs past the end of the frame.
            val body =
                ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES + Short.SIZE_BYTES).apply {
                    putShort(ApiKey.FETCH.id)
                    putShort(Protocol.VERSION)
                    putInt(31)
                    putShort(9999)
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
            assertEquals(31, response.int, "the header parsed, so the id is known and must be echoed")
            assertEquals(ErrorCode.CORRUPT_REQUEST, ErrorCode.of(response.short))
        }
    }

    @Test
    fun `every code the protocol document lists is reachable or reserved on purpose`() {
        // A guard against the enum and the document drifting apart, and it earned its keep in
        // M-160: adding PARTITION_UNAVAILABLE failed here first, which is the point — a code is on
        // the wire and in six clients, so it may not appear because somebody found it convenient.
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6),
            ErrorCode.entries.map { it.id.toInt() },
            "ids are on the wire and must not be renumbered",
        )
    }

    @Test
    fun `an unrecognised code is read as CORRUPT_REQUEST rather than crashing`() {
        // What a client of an older build does when a newer broker answers with a code it has never
        // heard of. It has to survive that: this is exactly the situation PARTITION_UNAVAILABLE put
        // every already-published client into.
        assertEquals(ErrorCode.CORRUPT_REQUEST, ErrorCode.of(99))
    }

    private fun writeHeaderOnly(
        socket: SocketChannel,
        apiKey: Short,
        version: Short,
        correlationId: Int,
    ) {
        val body =
            ByteBuffer.allocate(Protocol.REQUEST_HEADER_BYTES).apply {
                putShort(apiKey)
                putShort(version)
                putInt(correlationId)
                flip()
            }
        val frame =
            ByteBuffer.allocate(Protocol.LENGTH_PREFIX_BYTES + body.remaining()).apply {
                putInt(body.remaining())
                put(body)
                flip()
            }
        writeFully(socket, frame)
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
