package ru.workinprogress.booblik.java;

/** How long the broker waits before answering. On the wire as a single byte, in this order. */
public enum AckPolicy {

    /**
     * Answers nothing at all — not an empty response, nothing, because no offset exists until the
     * writer reaches the batch. It is also the only mode in which the broker may lose an accepted
     * record without the client hearing about it, or about being overloaded.
     */
    NONE,

    /** Answers once the record is in the log, before any durability barrier. */
    WRITTEN,

    /**
     * Answers after {@code force()}. Not one barrier per request: the broker groups everyone
     * already queued into one.
     */
    FORCED;

    byte id() {
        return (byte) ordinal();
    }
}
