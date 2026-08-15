package ru.workinprogress.booblik.java;

/**
 * The bytes on the connection do not make sense — a length out of range, a response that ended
 * early, or an answer carrying somebody else's correlation id.
 *
 * <p>Its own type rather than an {@link java.io.IOException}, because the two mean different things
 * to a caller: a socket that dropped is worth retrying, and a broker that answered nonsense is not.
 */
public final class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super("booblik: " + message);
    }
}
