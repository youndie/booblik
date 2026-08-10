package ru.workinprogress.booblik.net.nio

import ru.workinprogress.booblik.Position
import ru.workinprogress.booblik.storage.LogSegment
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.nio.channels.WritableByteChannel

/**
 * One client connection, as the session loop sees it.
 *
 * The abstraction earns its place by making M-36 an honest comparison. Two transports are measured
 * against each other — a selector with coroutines, and blocking sockets on virtual threads — and if
 * each came with its own session loop, the benchmark would be comparing two programs rather than
 * two transports. Here the protocol logic is written once and both transports plug in underneath.
 *
 * Every method suspends by signature. On the blocking transport nothing actually suspends; the
 * virtual thread parks instead, which is the same thing from the session's point of view and the
 * whole reason the shape fits both.
 */
interface Connection : Closeable {
    /**
     * The channel [transferFrom] hands to `FileChannel.transferTo`.
     *
     * Exposed so the property it must have can be checked rather than assumed: the JDK takes the
     * `sendfile` path **only** when this is a `SocketChannelImpl`, and wrapping the socket in any
     * decorator silently turns the read path into a copy loop that returns identical bytes
     * (M-63, `SendfileTest`).
     */
    val transferTarget: WritableByteChannel

    /** Fills [buffer] completely, or throws if the peer goes away first. */
    suspend fun readFully(buffer: ByteBuffer)

    /** Writes [buffer] completely. */
    suspend fun writeFully(buffer: ByteBuffer)

    /**
     * Streams [bytes] from [segment] starting at [position] straight into the socket.
     *
     * Loops until everything is gone. A single `transferTo` is allowed to move less than asked and
     * to return zero outright when the send buffer is full — treating zero as "done" truncates
     * responses, and treating it as "try again immediately" burns a core (research §1.4). Both
     * implementations wait for writability instead.
     */
    suspend fun transferFrom(
        segment: LogSegment,
        position: Position,
        bytes: Int,
    )
}

/** Non-blocking transport: readiness comes from [SelectorLoop], the coroutine suspends meanwhile. */
class SelectorConnection(
    private val channel: SocketChannel,
    private val key: SelectionKey,
    private val loop: SelectorLoop,
) : Connection {
    override val transferTarget: WritableByteChannel get() = channel

    override suspend fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) throw java.io.EOFException("peer closed the connection mid-frame")
            if (read == 0) loop.awaitReadable(key)
        }
    }

    override suspend fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) loop.awaitWritable(key)
        }
    }

    override suspend fun transferFrom(
        segment: LogSegment,
        position: Position,
        bytes: Int,
    ) {
        var moved = 0
        while (moved < bytes) {
            val sent = segment.transferTo(position + moved, bytes - moved, channel)
            if (sent == 0L) {
                loop.awaitWritable(key)
            } else {
                moved += sent.toInt()
            }
        }
    }

    override fun close() {
        key.cancel()
        channel.close()
    }
}

/**
 * Blocking transport, meant to be run on a virtual thread — the baseline M-36 measures the selector
 * against.
 *
 * There is one thing here that a virtual thread does not fix, and it is the reason this is a
 * baseline rather than a candidate: `transferTo` blocks on **disk** I/O, and file I/O is not
 * virtualised. A page fault inside `sendfile` pins the carrier thread (research §1.4), so this
 * transport trades a selector for a thread pool that can be stalled by a slow disk.
 */
class BlockingConnection(
    private val channel: SocketChannel,
) : Connection {
    override val transferTarget: WritableByteChannel get() = channel

    override suspend fun readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw java.io.EOFException("peer closed the connection mid-frame")
        }
    }

    override suspend fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override suspend fun transferFrom(
        segment: LogSegment,
        position: Position,
        bytes: Int,
    ) {
        var moved = 0
        while (moved < bytes) {
            val sent = segment.transferTo(position + moved, bytes - moved, channel)
            // A blocking socket returns zero only when the peer has stopped reading entirely and
            // the send buffer is full; there is nothing to wait on but the peer, and the write
            // itself is what waits.
            if (sent == 0L) continue
            moved += sent.toInt()
        }
    }

    override fun close() {
        channel.close()
    }
}
