package ru.workinprogress.booblik.java;

/** Tunes the accumulator. */
public record ProducerConfig(int maxBatchSize, long lingerMillis, AckPolicy ack) {

    /**
     * Matches the other clients' defaults, so the same program batches the same way whichever one
     * it uses.
     *
     * <p>A linger of zero is not the fast setting. It sends every record on its own, which the
     * broker's own measurements put at 80 592 records/s against 4 335 482 for batches of a hundred
     * — the accumulator is the single largest performance factor in this project.
     */
    public static ProducerConfig defaults() {
        return new ProducerConfig(100, 5, AckPolicy.WRITTEN);
    }
}
