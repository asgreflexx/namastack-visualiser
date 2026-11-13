package at.unleash.it.outboxvisualiser.action;

import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Changes outbox records the way Namastack itself would: every action is a single statement guarded
 * by the expected status, so it is atomic and safe against concurrent processing. A record that a
 * Namastack instance changed in the meantime is simply not affected.
 */
@Service
public class RecordActionService {

    private final Clock clock;
    private final AuditLog auditLog;

    public RecordActionService(Clock clock, AuditLog auditLog) {
        this.clock = clock;
        this.auditLog = auditLog;
    }

    /** FAILED to NEW with reset failure count, due immediately. This releases the blocked record key. */
    public ActionResult retry(OutboxSource source, String recordId, String user) {
        requireWritable(source);
        int affected = source.jdbc()
                .sql("""
                        UPDATE %s
                        SET status = :newStatus, failure_count = 0, failure_reason = NULL, next_retry_at = :now
                        WHERE id = :id AND status = :failedStatus""".formatted(source.tables().record()))
                .param("newStatus", RecordStatus.NEW.name())
                .param("failedStatus", RecordStatus.FAILED.name())
                .param("now", SqlTime.toTimestamp(clock.instant()))
                .param("id", recordId)
                .update();
        return finish(source, user, RecordAction.RETRY, recordId, affected, recordId,
                affected > 0 ? "Record wird erneut verarbeitet." : "Record ist nicht (mehr) FAILED.");
    }

    /** Makes a NEW record that waits for its retry backoff due immediately. */
    public ActionResult runNow(OutboxSource source, String recordId, String user) {
        requireWritable(source);
        int affected = source.jdbc()
                .sql("UPDATE " + source.tables().record() + " SET next_retry_at = :now WHERE id = :id AND status = :newStatus")
                .param("newStatus", RecordStatus.NEW.name())
                .param("now", SqlTime.toTimestamp(clock.instant()))
                .param("id", recordId)
                .update();
        return finish(source, user, RecordAction.RUN_NOW, recordId, affected, recordId,
                affected > 0 ? "Record ist sofort fällig." : "Record ist nicht (mehr) NEW.");
    }

    /**
     * Inserts a copy of a COMPLETED record as a new NEW record. The copy keeps key, type, payload,
     * context, handler and partition, gets a new UUIDv7 id and is ordered after all existing records
     * of its key.
     */
    public ActionResult replay(OutboxSource source, String recordId, String user) {
        requireWritable(source);
        String newId = UuidV7.generate(clock).toString();
        Instant now = clock.instant();
        String table = source.tables().record();
        int affected = source.jdbc()
                .sql("""
                        INSERT INTO %1$s
                          (id, status, record_key, record_type, payload, context, partition_no,
                           created_at, completed_at, failure_count, failure_reason, next_retry_at, handler_id)
                        SELECT :newId, :newStatus, record_key, record_type, payload, context, partition_no,
                               :now, NULL, 0, NULL, :now, handler_id
                        FROM %1$s
                        WHERE id = :id AND status = :completedStatus""".formatted(table))
                .param("newId", newId)
                .param("newStatus", RecordStatus.NEW.name())
                .param("completedStatus", RecordStatus.COMPLETED.name())
                .param("now", SqlTime.toTimestamp(now))
                .param("id", recordId)
                .update();
        return finish(source, user, RecordAction.REPLAY, recordId, affected, affected > 0 ? newId : recordId,
                affected > 0 ? "Kopie " + newId + " angelegt." : "Record ist nicht (mehr) vorhanden oder nicht COMPLETED.");
    }

    /** Number of FAILED records a {@link #bulkRetry} with the same arguments would affect. */
    public long countFailed(OutboxSource source, String handlerId, String recordType) {
        BulkCondition condition = BulkCondition.of(handlerId, recordType);
        return source.jdbc()
                .sql("SELECT COUNT(*) FROM " + source.tables().record() + " WHERE status = :failedStatus" + condition.sql())
                .param("failedStatus", RecordStatus.FAILED.name())
                .params(condition.params())
                .query(Long.class)
                .single();
    }

    /** {@link #retry} for all FAILED records, optionally restricted to one handler and/or record type. */
    public ActionResult bulkRetry(OutboxSource source, String handlerId, String recordType, String user) {
        requireWritable(source);
        BulkCondition condition = BulkCondition.of(handlerId, recordType);
        int affected = source.jdbc()
                .sql("""
                        UPDATE %s
                        SET status = :newStatus, failure_count = 0, failure_reason = NULL, next_retry_at = :now
                        WHERE status = :failedStatus""".formatted(source.tables().record()) + condition.sql())
                .param("newStatus", RecordStatus.NEW.name())
                .param("failedStatus", RecordStatus.FAILED.name())
                .param("now", SqlTime.toTimestamp(clock.instant()))
                .params(condition.params())
                .update();
        String target = condition.describe();
        return finish(source, user, RecordAction.BULK_RETRY, target, affected, null,
                affected + " Record(s) erneut eingeplant.");
    }

    private ActionResult finish(OutboxSource source, String user, RecordAction action, String target, int affected,
                                String resultId, String message) {
        auditLog.record(new AuditLog.Entry(clock.instant(), user, source.id(), action, target, affected));
        return new ActionResult(action, affected, resultId, message);
    }

    private static void requireWritable(OutboxSource source) {
        if (source.readOnly()) {
            throw new ReadOnlySourceException(source.id());
        }
    }

    private record BulkCondition(String sql, Map<String, Object> params, String describe) {

        static BulkCondition of(String handlerId, String recordType) {
            StringBuilder sql = new StringBuilder();
            Map<String, Object> params = new LinkedHashMap<>();
            List<String> description = new ArrayList<>();
            if (handlerId != null && !handlerId.isBlank()) {
                sql.append(" AND handler_id = :handlerId");
                params.put("handlerId", handlerId);
                description.add("handler=" + handlerId);
            }
            if (recordType != null && !recordType.isBlank()) {
                sql.append(" AND record_type = :recordType");
                params.put("recordType", recordType);
                description.add("type=" + recordType);
            }
            return new BulkCondition(sql.toString(), params, description.isEmpty() ? "all FAILED" : String.join(", ", description));
        }
    }
}
