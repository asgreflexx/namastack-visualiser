package at.unleash.it.outboxvisualiser.action;

/** Write actions on outbox records. */
public enum RecordAction {
    /** FAILED to NEW with a fresh retry budget; unblocks the record key. */
    RETRY("Retry"),
    /** NEW record waiting for its backoff becomes due immediately. */
    RUN_NOW("Jetzt ausführen"),
    /** COMPLETED record is copied into a new NEW record and processed again. */
    REPLAY("Replay"),
    /** {@link #RETRY} for all FAILED records matching a filter. */
    BULK_RETRY("Bulk-Retry");

    private final String label;

    RecordAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
