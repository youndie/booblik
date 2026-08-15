package ru.workinprogress.booblik.java;

/**
 * A record whose bytes do not match the checksum stored with them.
 *
 * <p>The client is the only party that can notice. On the zero-copy read path the broker sends
 * segment bytes to the socket without looking at them — that is what zero-copy means — so the sum is
 * computed once at write time to protect the <b>disk</b>, and verified once at read time, by whoever
 * finally holds the bytes. A client that skips it silently switches off the project's only defence
 * against a corrupted log.
 */
public final class CorruptRecordException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final long offset;
    private final int stored;
    private final int computed;

    CorruptRecordException(long offset, int stored, int computed) {
        super(
                "booblik: record at offset %d fails its checksum: stored 0x%08x, computed 0x%08x"
                        .formatted(offset, stored, computed));
        this.offset = offset;
        this.stored = stored;
        this.computed = computed;
    }

    /** Offset of the record, so a corrupted log can be inspected rather than merely feared. */
    public long offset() {
        return offset;
    }

    public int stored() {
        return stored;
    }

    public int computed() {
        return computed;
    }
}
