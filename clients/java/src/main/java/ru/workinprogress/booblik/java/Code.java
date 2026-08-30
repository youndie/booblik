package ru.workinprogress.booblik.java;

/** Why the broker refused a request. Values are on the wire; see docs/api/protocol-wire.md §5. */
public enum Code {
    NONE(0),
    UNKNOWN_TOPIC_OR_PARTITION(1),
    OFFSET_OUT_OF_RANGE(2),
    RECORD_TOO_LARGE(3),
    UNSUPPORTED_VERSION(4),
    CORRUPT_REQUEST(5),

    /**
     * The partition's writer died — a full volume being the case it was added for. Retrying does
     * not help; reads from the same partition still work.
     */
    PARTITION_UNAVAILABLE(6);

    private final short id;

    Code(int id) {
        this.id = (short) id;
    }

    public short id() {
        return id;
    }

    /**
     * Anything unrecognised is read as a corrupt request rather than throwing: the connection is
     * still framed correctly, and a client that died here would turn a newer broker's new code into
     * an outage.
     */
    public static Code of(short id) {
        for (Code code : values()) {
            if (code.id == id) {
                return code;
            }
        }
        return CORRUPT_REQUEST;
    }
}
