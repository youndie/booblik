package ru.workinprogress.booblik.native

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_UNSPEC
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.TCP_NODELAY
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.socket

/**
 * A blocking TCP socket over `platform.posix`.
 *
 * POSIX directly rather than a networking library, and that is the same choice every other booblik
 * client made: the whole of a publisher's transport is connect, send and receive, and a dependency
 * would be a larger thing than the code it replaces.
 *
 * `getaddrinfo` rather than `inet_pton`, for two reasons that both bite in practice: it accepts a
 * host name and not only a dotted quad, and it fills in a `sockaddr` whose layout differs between
 * Linux and the BSD-derived macOS — `sin_len` exists on one and not the other — so letting libc
 * build it is what makes one source file serve both targets.
 */
@OptIn(ExperimentalForeignApi::class)
internal class Socket private constructor(
    private val descriptor: Int,
) {
    companion object {
        fun connect(address: String): Socket {
            val separator = address.lastIndexOf(':')
            require(separator > 0) { "expected host:port, got '$address'" }
            val host = address.substring(0, separator)
            val port = address.substring(separator + 1)

            memScoped {
                val hints =
                    alloc<addrinfo>().apply {
                        ai_family = AF_UNSPEC
                        ai_socktype = SOCK_STREAM
                    }
                val resolved = allocPointerTo<addrinfo>()

                if (getaddrinfo(host, port, hints.ptr, resolved.ptr) != 0) {
                    throw ConnectionException("cannot resolve $address")
                }

                try {
                    var candidate = resolved.value
                    while (candidate != null) {
                        val info = candidate.pointed
                        val descriptor = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
                        if (descriptor >= 0) {
                            if (connect(descriptor, info.ai_addr, info.ai_addrlen) == 0) {
                                enableNoDelay(descriptor)
                                return Socket(descriptor)
                            }
                            close(descriptor)
                        }
                        candidate = info.ai_next
                    }
                } finally {
                    freeaddrinfo(resolved.value)
                }
                throw ConnectionException("cannot connect to $address")
            }
        }

        /**
         * The requests are small and every one of them is a round trip, so Nagle's algorithm would
         * add a delayed acknowledgement to each. Failing to set it is not fatal, which is why the
         * result is not checked: a slower connection beats no connection.
         */
        private fun enableNoDelay(descriptor: Int) {
            memScoped {
                val enabled = alloc<IntVar>()
                enabled.value = 1
                setsockopt(
                    descriptor,
                    IPPROTO_TCP,
                    TCP_NODELAY,
                    enabled.ptr,
                    sizeOf<IntVar>().convert(),
                )
            }
        }
    }

    fun writeFully(bytes: ByteArray) {
        bytes.usePinned { pinned ->
            var written = 0
            while (written < bytes.size) {
                val sent =
                    send(
                        descriptor,
                        pinned.addressOf(written),
                        (bytes.size - written).convert(),
                        0,
                    ).toInt()
                if (sent <= 0) throw ConnectionException("connection closed while sending")
                written += sent
            }
        }
    }

    fun readFully(count: Int): ByteArray {
        val bytes = ByteArray(count)
        bytes.usePinned { pinned ->
            var read = 0
            while (read < count) {
                val received =
                    recv(
                        descriptor,
                        pinned.addressOf(read),
                        (count - read).convert(),
                        0,
                    ).toInt()
                // Zero means an orderly close by the broker, which mid-frame is still a failure:
                // the caller asked for a whole frame and is not getting one.
                if (received <= 0) {
                    throw ConnectionException("broker closed the connection with ${count - read} bytes to go")
                }
                read += received
            }
        }
        return bytes
    }

    fun close() {
        close(descriptor)
    }
}
