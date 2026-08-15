package ru.workinprogress.booblik.java;

import java.util.List;

/**
 * One FETCH response, unframed and checksum-verified.
 *
 * @param highWatermark the first offset that does not exist yet, as of this response
 * @param truncated whether the response ended inside a record. Routine rather than exceptional:
 *     {@code maxBytes} cuts on a byte boundary, so a full response normally ends this way
 * @param truncatedRecordBytes how big the dropped record is, when its header made it into the
 *     response; zero when the response stopped inside the header itself
 */
public record Fetched(
        long highWatermark, List<byte[]> records, boolean truncated, int truncatedRecordBytes) {}
