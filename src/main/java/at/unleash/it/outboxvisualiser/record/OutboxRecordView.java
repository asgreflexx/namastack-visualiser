package at.unleash.it.outboxvisualiser.record;

import java.time.Duration;
import java.time.Instant;

/**
 * One row of the Namastack {@code outbox_record} table. {@code payload} and {@code context} are
 * only loaded for the detail view and are {@code null} in list queries.
 */
public record OutboxRecordView(
        String sourceId,
        String id,
        RecordStatus status,
        String recordKey,
        String recordType,
        String payload,
        String context,
        int partitionNo,
        Instant createdAt,
        Instant completedAt,
        int failureCount,
        String failureReason,
        Instant nextRetryAt,
        String handlerId) {

    public String shortType() {
        return Names.simpleTypeName(recordType);
    }

    public String shortHandler() {
        return Names.shortHandler(handlerId);
    }

    /** Processing time from creation to completion, or {@code null} while incomplete. */
    public Duration processingTime() {
        return completedAt == null ? null : Duration.between(createdAt, completedAt);
    }

    /** A NEW record waiting for its backoff to expire. */
    public boolean isScheduledLater(Instant now) {
        return status == RecordStatus.NEW && nextRetryAt != null && nextRetryAt.isAfter(now);
    }

    public boolean canRetry() {
        return status == RecordStatus.FAILED;
    }

    public boolean canRunNow() {
        return status == RecordStatus.NEW;
    }

    public boolean canReplay() {
        return status == RecordStatus.COMPLETED;
    }
}
