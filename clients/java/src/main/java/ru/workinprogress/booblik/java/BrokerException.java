package ru.workinprogress.booblik.java;

/**
 * A refusal from the broker, as opposed to anything going wrong with the connection.
 *
 * <p>A refusal is a result and not a transport failure: the connection stays usable, because framing
 * was intact — the broker understood the request and declined it. Only a frame length outside the
 * allowed range closes a connection.
 */
public final class BrokerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Code code;

    public BrokerException(Code code) {
        super("booblik: broker refused the request: " + code);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
