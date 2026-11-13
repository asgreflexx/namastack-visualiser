package at.unleash.it.outboxvisualiser.record;

import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.SqlTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read access to the Namastack {@code outbox_record} table. The repository is stateless; every
 * method takes the source to query.
 */
@Repository
public class OutboxRecordRepository {

    private static final String LIST_COLUMNS = """
            id, status, record_key, record_type, partition_no, created_at, completed_at,
            failure_count, failure_reason, next_retry_at, handler_id""";

    private static final String ALL_COLUMNS = LIST_COLUMNS + ", payload, context";

    public PageResult<OutboxRecordView> find(OutboxSource source, RecordFilter filter, int page, int size) {
        Where where = Where.of(filter);
        String table = source.tables().record();

        long total = source.jdbc()
                .sql("SELECT COUNT(*) FROM " + table + where.sql())
                .params(where.params())
                .query(Long.class)
                .single();

        String select = "SELECT " + LIST_COLUMNS + " FROM " + table + where.sql() + " ORDER BY created_at DESC, id DESC";
        List<OutboxRecordView> items = source.jdbc()
                .sql(source.dialect().paginate(select))
                .params(where.params())
                .param("limit", size)
                .param("offset", page * size)
                .query(mapper(source, false))
                .list();
        return new PageResult<>(items, page, size, total);
    }

    public Optional<OutboxRecordView> findById(OutboxSource source, String id) {
        return source.jdbc()
                .sql("SELECT " + ALL_COLUMNS + " FROM " + source.tables().record() + " WHERE id = :id")
                .param("id", id)
                .query(mapper(source, true))
                .optional();
    }

    /** All records of one key in processing order, the order Namastack uses to enforce sequencing. */
    public List<OutboxRecordView> findByKey(OutboxSource source, String recordKey, int limit) {
        String select = "SELECT " + LIST_COLUMNS + " FROM " + source.tables().record()
                + " WHERE record_key = :recordKey ORDER BY created_at, id";
        return source.jdbc()
                .sql(source.dialect().paginate(select))
                .param("recordKey", recordKey)
                .param("limit", limit)
                .param("offset", 0)
                .query(mapper(source, false))
                .list();
    }

    public Map<RecordStatus, Long> countByStatus(OutboxSource source) {
        Map<RecordStatus, Long> counts = new EnumMap<>(RecordStatus.class);
        for (RecordStatus status : RecordStatus.values()) {
            counts.put(status, 0L);
        }
        source.jdbc()
                .sql("SELECT status, COUNT(*) AS cnt FROM " + source.tables().record() + " GROUP BY status")
                .query((rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("cnt")))
                .list()
                .forEach(row -> parseStatus(row.getKey()).ifPresent(status -> counts.put(status, row.getValue())));
        return counts;
    }

    public Optional<Instant> oldestCreatedAt(OutboxSource source, RecordStatus status) {
        return source.jdbc()
                .sql("SELECT MIN(created_at) AS oldest FROM " + source.tables().record() + " WHERE status = :status")
                .param("status", status.name())
                .query((rs, rowNum) -> SqlTime.instant(rs, "oldest"))
                .optional();
    }

    public long countBlockedKeys(OutboxSource source) {
        return source.jdbc()
                .sql("SELECT COUNT(DISTINCT record_key) FROM " + source.tables().record() + " WHERE status = :status")
                .param("status", RecordStatus.FAILED.name())
                .query(Long.class)
                .single();
    }

    /**
     * Counts records per time bucket for {@code created_at} or {@code completed_at}. Bucketing is
     * done with {@code CASE} expressions over bound bucket boundaries, which works the same on all
     * databases, in a single scan of the time range.
     */
    public long[] countPerBucket(OutboxSource source, TimeColumn column, Instant from, Duration bucket, int buckets) {
        StringBuilder sql = new StringBuilder("SELECT ");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < buckets; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("SUM(CASE WHEN ").append(column.sql).append(" >= :b").append(i)
                    .append(" AND ").append(column.sql).append(" < :b").append(i + 1)
                    .append(" THEN 1 ELSE 0 END) AS c").append(i);
            params.put("b" + i, SqlTime.toTimestamp(from.plus(bucket.multipliedBy(i))));
        }
        params.put("b" + buckets, SqlTime.toTimestamp(from.plus(bucket.multipliedBy(buckets))));
        sql.append(" FROM ").append(source.tables().record())
                .append(" WHERE ").append(column.sql).append(" >= :b0 AND ").append(column.sql).append(" < :b").append(buckets);

        return source.jdbc()
                .sql(sql.toString())
                .params(params)
                .query((rs, rowNum) -> {
                    long[] counts = new long[buckets];
                    for (int i = 0; i < buckets; i++) {
                        counts[i] = rs.getLong("c" + i);
                    }
                    return counts;
                })
                .single();
    }

    public List<FailureGroup> failuresByHandler(OutboxSource source, int limit) {
        String select = "SELECT handler_id, record_type, COUNT(*) AS cnt FROM " + source.tables().record()
                + " WHERE status = :status GROUP BY handler_id, record_type ORDER BY cnt DESC, handler_id";
        return source.jdbc()
                .sql(source.dialect().paginate(select))
                .param("status", RecordStatus.FAILED.name())
                .param("limit", limit)
                .param("offset", 0)
                .query((rs, rowNum) -> new FailureGroup(rs.getString("handler_id"), rs.getString("record_type"), rs.getLong("cnt")))
                .list();
    }

    /** FAILED records, oldest first, with the number of NEW records of the same key waiting behind them. */
    public List<BlockedKey> blockedKeys(OutboxSource source, int limit) {
        String table = source.tables().record();
        String select = """
                SELECT f.id, f.record_key, f.record_type, f.handler_id, f.failure_count, f.failure_reason, f.created_at,
                       (SELECT COUNT(*) FROM %1$s w
                         WHERE w.record_key = f.record_key
                           AND w.status = :newStatus
                           AND (w.created_at > f.created_at OR (w.created_at = f.created_at AND w.id > f.id))) AS waiting
                FROM %1$s f
                WHERE f.status = :failedStatus
                ORDER BY f.created_at, f.id""".formatted(table);
        return source.jdbc()
                .sql(source.dialect().paginate(select))
                .param("newStatus", RecordStatus.NEW.name())
                .param("failedStatus", RecordStatus.FAILED.name())
                .param("limit", limit)
                .param("offset", 0)
                .query((rs, rowNum) -> new BlockedKey(
                        source.id(),
                        rs.getString("id"),
                        rs.getString("record_key"),
                        rs.getString("record_type"),
                        rs.getString("handler_id"),
                        rs.getInt("failure_count"),
                        rs.getString("failure_reason"),
                        SqlTime.instant(rs, "created_at"),
                        rs.getLong("waiting")))
                .list();
    }

    /** Number of incomplete (NEW or FAILED) records per partition. */
    public Map<Integer, Long> openRecordsPerPartition(OutboxSource source) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        source.jdbc()
                .sql("SELECT partition_no, COUNT(*) AS cnt FROM " + source.tables().record()
                        + " WHERE status IN (:statuses) GROUP BY partition_no")
                .param("statuses", List.of(RecordStatus.NEW.name(), RecordStatus.FAILED.name()))
                .query((rs, rowNum) -> Map.entry(rs.getInt("partition_no"), rs.getLong("cnt")))
                .list()
                .forEach(row -> counts.put(row.getKey(), row.getValue()));
        return counts;
    }

    public List<String> distinctRecordTypes(OutboxSource source) {
        return distinct(source, "record_type");
    }

    public List<String> distinctHandlerIds(OutboxSource source) {
        return distinct(source, "handler_id");
    }

    private static List<String> distinct(OutboxSource source, String column) {
        return source.jdbc()
                .sql("SELECT DISTINCT " + column + " FROM " + source.tables().record() + " ORDER BY " + column)
                .query(String.class)
                .list();
    }

    private static RowMapper<OutboxRecordView> mapper(OutboxSource source, boolean withPayload) {
        return (rs, rowNum) -> new OutboxRecordView(
                source.id(),
                rs.getString("id"),
                RecordStatus.valueOf(rs.getString("status")),
                rs.getString("record_key"),
                rs.getString("record_type"),
                withPayload ? rs.getString("payload") : null,
                withPayload ? rs.getString("context") : null,
                rs.getInt("partition_no"),
                SqlTime.instant(rs, "created_at"),
                SqlTime.instant(rs, "completed_at"),
                rs.getInt("failure_count"),
                rs.getString("failure_reason"),
                SqlTime.instant(rs, "next_retry_at"),
                rs.getString("handler_id"));
    }

    private static Optional<RecordStatus> parseStatus(String value) {
        try {
            return Optional.of(RecordStatus.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public enum TimeColumn {
        CREATED_AT("created_at"),
        COMPLETED_AT("completed_at");

        private final String sql;

        TimeColumn(String sql) {
            this.sql = sql;
        }
    }

    /** WHERE clause built from a {@link RecordFilter}; only column names are concatenated, values are bound. */
    record Where(String sql, Map<String, Object> params) {

        static Where of(RecordFilter filter) {
            List<String> conditions = new ArrayList<>();
            Map<String, Object> params = new LinkedHashMap<>();
            if (filter.status() != null) {
                conditions.add("status = :status");
                params.put("status", filter.status().name());
            }
            if (hasText(filter.recordType())) {
                conditions.add("record_type = :recordType");
                params.put("recordType", filter.recordType());
            }
            if (hasText(filter.handlerId())) {
                conditions.add("handler_id = :handlerId");
                params.put("handlerId", filter.handlerId());
            }
            if (hasText(filter.recordKey())) {
                conditions.add("record_key LIKE :recordKey ESCAPE '!'");
                params.put("recordKey", escapeLike(filter.recordKey().strip()) + "%");
            }
            if (filter.createdFrom() != null) {
                conditions.add("created_at >= :createdFrom");
                params.put("createdFrom", SqlTime.toTimestamp(filter.createdFrom()));
            }
            if (filter.createdTo() != null) {
                conditions.add("created_at < :createdTo");
                params.put("createdTo", SqlTime.toTimestamp(filter.createdTo()));
            }
            String sql = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
            return new Where(sql, params);
        }

        private static String escapeLike(String value) {
            return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
