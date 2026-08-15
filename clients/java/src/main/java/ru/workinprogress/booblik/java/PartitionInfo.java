package ru.workinprogress.booblik.java;

/**
 * One partition, as the broker describes it.
 *
 * @param logStartOffset where the <b>live</b> log begins, after retention. Reading "from the start"
 *     means starting here and not at zero — zero is OFFSET_OUT_OF_RANGE on any topic that has ever
 *     dropped a segment.
 * @param highWatermark the first offset that does not exist yet.
 */
public record PartitionInfo(int partition, long logStartOffset, long highWatermark) {}
