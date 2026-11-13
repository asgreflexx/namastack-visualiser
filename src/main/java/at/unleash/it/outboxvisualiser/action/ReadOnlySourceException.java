package at.unleash.it.outboxvisualiser.action;

public class ReadOnlySourceException extends RuntimeException {

    public ReadOnlySourceException(String sourceId) {
        super("Outbox source '" + sourceId + "' is configured read-only");
    }
}
