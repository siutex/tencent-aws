package edgeone.origin.common;

/** No usable value: never loaded, or older than the hard limit. Callers must fail closed. */
public final class ValueUnavailableException extends Exception {

    public ValueUnavailableException(String message) {
        super(message);
    }

    public ValueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
