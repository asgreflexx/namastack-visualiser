package at.unleash.it.outboxvisualiser.action;

/**
 * Outcome of a write action.
 *
 * @param recordId the affected record, or for a replay the newly created record; null for bulk actions
 */
public record ActionResult(RecordAction action, int affected, String recordId, String message) {

    public boolean success() {
        return affected > 0;
    }
}
