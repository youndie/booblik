package ru.workinprogress.booblik.java;

/**
 * The next record is larger than {@code maxBytes}, so it can never arrive whole.
 *
 * <p>One of the two ways a consumer stalls, and the one that does not resolve itself. A response
 * with no whole records and a truncated tail means the record does not fit; a client that drops the
 * tail and retries makes exactly the same request for ever — running, reporting nothing, never
 * advancing. Thrown rather than retried, because raising {@code maxBytes} is the only fix.
 *
 * <p>Not to be confused with {@link Code#RECORD_TOO_LARGE}, which is the broker refusing to
 * <b>store</b> a record too big for a segment. This one is the reader's own limit, chosen by the
 * reader.
 */
public final class RecordExceedsMaxBytesException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final long offset;
    private final int recordBytes;
    private final int maxBytes;

    RecordExceedsMaxBytesException(long offset, int recordBytes, int maxBytes) {
        super(
                ("booblik: record at offset %d needs %d bytes and maxBytes is %d, "
                                + "so it can never be read whole")
                        .formatted(offset, recordBytes, maxBytes));
        this.offset = offset;
        this.recordBytes = recordBytes;
        this.maxBytes = maxBytes;
    }

    public long offset() {
        return offset;
    }

    public int recordBytes() {
        return recordBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }
}
