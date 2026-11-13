package at.unleash.it.outboxvisualiser.record;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Filter of the record list, bound from request parameters. Empty values mean "no restriction".
 *
 * @param recordKey matches keys starting with this value
 */
public record RecordFilter(
        String source,
        RecordStatus status,
        String recordType,
        String handlerId,
        String recordKey,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo) {

    public static RecordFilter none() {
        return new RecordFilter(null, null, null, null, null, null, null);
    }

    public static RecordFilter ofStatus(RecordStatus status) {
        return new RecordFilter(null, status, null, null, null, null, null);
    }
}
