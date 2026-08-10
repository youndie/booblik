package ru.workinprogress.booblik.net.wire

/**
 * Constants of the wire format. The prose version, with the reasoning, is `docs/api/protocol-wire.md`;
 * if the two disagree, the document is wrong and gets fixed in the same commit.
 *
 * Everything is big-endian, and that is not a coin toss: it matches the on-disk record framing, so
 * a FETCH response can hand segment bytes to the socket without touching them. A protocol that
 * disagreed with the log would need a byte swap on the one path that must not have one.
 */
object Protocol {
    const val VERSION: Short = 1

    /** `[int32 length][int16 apiKey][int16 apiVersion][int32 correlationId]`. */
    const val REQUEST_HEADER_BYTES = 2 + 2 + 4

    /** `[int32 correlationId][int16 errorCode]`, after the length prefix. */
    const val RESPONSE_HEADER_BYTES = 4 + 2

    const val LENGTH_PREFIX_BYTES = 4

    /**
     * Ceiling on one request. A length prefix arriving from a socket is attacker-controlled, and
     * allocating whatever it asks for is how a broker gets killed by one packet — at a 64 MiB heap,
     * by a very small one.
     */
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    /** FETCH v2 adds `maxWaitMillis` and `minBytes`; everything else still speaks v1. */
    const val FETCH_VERSION: Short = 2

    /**
     * Ceiling on how long a FETCH may be held, whatever the client asked for.
     *
     * A client asking for an hour would hold a coroutine and a socket for an hour on somebody
     * else's say-so. Clamping instead of rejecting keeps the contract simple — the client gets an
     * earlier empty response, which it has to handle anyway — and the number is written down here
     * rather than hidden, because a silently shortened wait is a lie about the protocol.
     */
    const val MAX_FETCH_WAIT_MILLIS = 60_000

    /** Whether this broker speaks [version] of [apiKey]. Versions are per request, not global. */
    fun supports(
        apiKey: ApiKey,
        version: Short,
    ): Boolean =
        when (apiKey) {
            ApiKey.FETCH -> version == VERSION || version == FETCH_VERSION
            ApiKey.PRODUCE, ApiKey.METADATA -> version == VERSION
        }
}

/** What a request asks for. Values are on the wire and must not be renumbered. */
enum class ApiKey(
    val id: Short,
) {
    PRODUCE(1),
    FETCH(2),

    /**
     * Which topics exist and where each partition currently starts and ends.
     *
     * Added because a client cannot otherwise subscribe to a topic without being told its
     * partitions by hand: the set is fixed when the broker starts and nothing on the wire ever
     * mentioned it. This is **not** a metadata layer — there is no topic creation and no cluster
     * to describe — it is the one question a reader has to ask before it can read.
     */
    METADATA(3),
    ;

    companion object {
        fun of(id: Short): ApiKey? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Why a request failed. Values are on the wire and must not be renumbered.
 *
 * [NONE] is zero so that a success response needs no special case anywhere.
 */
enum class ErrorCode(
    val id: Short,
) {
    NONE(0),
    UNKNOWN_TOPIC_OR_PARTITION(1),
    OFFSET_OUT_OF_RANGE(2),
    RECORD_TOO_LARGE(3),
    UNSUPPORTED_VERSION(4),
    CORRUPT_REQUEST(5),
    ;

    companion object {
        fun of(id: Short): ErrorCode = entries.firstOrNull { it.id == id } ?: CORRUPT_REQUEST
    }
}

/** Thrown while decoding a frame that does not make sense. Answered with [ErrorCode.CORRUPT_REQUEST]. */
class CorruptRequestException(
    message: String,
) : IllegalArgumentException(message)
