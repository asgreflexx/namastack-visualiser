package at.unleash.it.outboxvisualiser.record;

import java.time.Instant;

/**
 * A FAILED record and the number of NEW records with the same key queued behind it. Namastack
 * does not process a record while an earlier record of its key is incomplete, so these records
 * wait until the failed one is retried.
 */
public record BlockedKey(
        String sourceId,
        String recordId,
        String recordKey,
        String recordType,
        String handlerId,
        int failureCount,
        String failureReason,
        Instant createdAt,
        long waitingRecords) {

    public String shortType() {
        return Names.simpleTypeName(recordType);
    }

    public String shortHandler() {
        return Names.shortHandler(handlerId);
    }
}
