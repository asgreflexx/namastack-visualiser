package at.unleash.it.outboxvisualiser.stats;

import at.unleash.it.outboxvisualiser.record.FailureGroup;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository.TimeColumn;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Aggregated numbers for the dashboard. Failures of one source never break the others. */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final OutboxRecordRepository repository;
    private final Clock clock;

    public StatsService(OutboxRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public SourceSummary summary(OutboxSource source) {
        try {
            Map<RecordStatus, Long> counts = repository.countByStatus(source);
            Duration lag = repository.oldestCreatedAt(source, RecordStatus.NEW)
                    .map(oldest -> Duration.between(oldest, clock.instant()))
                    .orElse(null);
            long blockedKeys = repository.countBlockedKeys(source);
            return new SourceSummary(source.id(), source.name(), source.readOnly(),
                    counts.get(RecordStatus.NEW), counts.get(RecordStatus.FAILED), counts.get(RecordStatus.COMPLETED),
                    lag, blockedKeys, null);
        } catch (RuntimeException e) {
            log.warn("Cannot read outbox source '{}': {}", source.id(), e.getMessage());
            return SourceSummary.unavailable(source, rootMessage(e));
        }
    }

    /** Created and completed records per bucket, summed over the given sources. */
    public Throughput throughput(List<OutboxSource> sources, Window window) {
        // Ends at the last full minute, so the running minute does not show up as a drop.
        Instant end = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Instant start = end.minus(window.bucket.multipliedBy(window.buckets));
        long[] created = new long[window.buckets];
        long[] completed = new long[window.buckets];
        for (OutboxSource source : sources) {
            try {
                add(created, repository.countPerBucket(source, TimeColumn.CREATED_AT, start, window.bucket, window.buckets));
                add(completed, repository.countPerBucket(source, TimeColumn.COMPLETED_AT, start, window.bucket, window.buckets));
            } catch (RuntimeException e) {
                log.warn("Cannot read throughput of source '{}': {}", source.id(), e.getMessage());
            }
        }
        List<Instant> bucketStarts = new ArrayList<>(window.buckets);
        for (int i = 0; i < window.buckets; i++) {
            bucketStarts.add(start.plus(window.bucket.multipliedBy(i)));
        }
        return new Throughput(bucketStarts, created, completed);
    }

    /** FAILED records per handler and type, merged over the given sources. */
    public List<FailureGroup> failures(List<OutboxSource> sources, int limit) {
        Map<String, FailureGroup> merged = new LinkedHashMap<>();
        for (OutboxSource source : sources) {
            try {
                for (FailureGroup group : repository.failuresByHandler(source, limit)) {
                    merged.merge(group.handlerId() + "|" + group.recordType(), group,
                            (a, b) -> new FailureGroup(a.handlerId(), a.recordType(), a.count() + b.count()));
                }
            } catch (RuntimeException e) {
                log.warn("Cannot read failures of source '{}': {}", source.id(), e.getMessage());
            }
        }
        return merged.values().stream()
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .limit(limit)
                .toList();
    }

    private static void add(long[] target, long[] values) {
        for (int i = 0; i < target.length; i++) {
            target[i] += values[i];
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    public record SourceSummary(String sourceId, String name, boolean readOnly, long newCount, long failedCount,
                                long completedCount, Duration lag, long blockedKeys, String error) {

        static SourceSummary unavailable(OutboxSource source, String error) {
            return new SourceSummary(source.id(), source.name(), source.readOnly(), 0, 0, 0, null, 0, error);
        }

        public boolean available() {
            return error == null;
        }
    }

    public record Throughput(List<Instant> bucketStarts, long[] created, long[] completed) {
    }

    public enum Window {
        M15(Duration.ofMinutes(1), 15),
        H1(Duration.ofMinutes(1), 60),
        H6(Duration.ofMinutes(5), 72),
        H24(Duration.ofMinutes(15), 96);

        private final Duration bucket;
        private final int buckets;

        Window(Duration bucket, int buckets) {
            this.bucket = bucket;
            this.buckets = buckets;
        }

        public Duration bucket() {
            return bucket;
        }
    }
}
