package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.OutboxRecordView;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * All records of one key laid out on a common time axis, with the state each record is in from
 * Namastack's point of view (processing happens strictly in order per key).
 */
public record KeyTimeline(String recordKey, Instant start, Instant end, List<Entry> entries, long waiting) {

    public static KeyTimeline of(String recordKey, List<OutboxRecordView> records, Instant now) {
        if (records.isEmpty()) {
            return new KeyTimeline(recordKey, now, now, List.of(), 0);
        }
        Instant start = records.getFirst().createdAt();
        Instant end = now;
        long span = Math.max(Duration.between(start, end).toMillis(), 1);

        List<Entry> entries = new ArrayList<>();
        boolean earlierIncomplete = false;
        long waiting = 0;
        for (OutboxRecordView record : records) {
            State state = switch (record.status()) {
                case COMPLETED -> State.DONE;
                case FAILED -> State.FAILED;
                case NEW -> earlierIncomplete ? State.WAITING
                        : record.isScheduledLater(now) ? State.BACKOFF : State.DUE;
            };
            if (state == State.WAITING) {
                waiting++;
            }
            if (record.status() != RecordStatus.COMPLETED) {
                earlierIncomplete = true;
            }
            Instant barEnd = record.completedAt() != null ? record.completedAt() : now;
            double left = 100.0 * Duration.between(start, record.createdAt()).toMillis() / span;
            double width = Math.max(100.0 * Duration.between(record.createdAt(), barEnd).toMillis() / span, 0.6);
            entries.add(new Entry(record, state, round(left), round(Math.min(width, 100 - left))));
        }
        return new KeyTimeline(recordKey, start, end, entries, waiting);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    public record Entry(OutboxRecordView record, State state, double leftPercent, double widthPercent) {
    }

    public enum State {
        DONE("abgeschlossen"),
        FAILED("fehlgeschlagen, blockiert Key"),
        DUE("bereit"),
        BACKOFF("wartet auf Retry-Backoff"),
        WAITING("wartet auf Vorgänger");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String css() {
            return name().toLowerCase();
        }
    }
}
