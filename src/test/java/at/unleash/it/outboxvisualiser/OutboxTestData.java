package at.unleash.it.outboxvisualiser;

import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.SqlTime;
import at.unleash.it.outboxvisualiser.source.TableNames;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties.TableProperties;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Creates H2 sources with the original Namastack schema and writes records the way Namastack does. */
public final class OutboxTestData {

    private OutboxTestData() {
    }

    public static OutboxSource h2Source() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("demo/schema-h2.sql")).execute(dataSource);
        TableNames tables = TableNames.resolve(null, "", new TableProperties("outbox_record", "outbox_instance", "outbox_partition"));
        return new OutboxSource("test", "Test", false, tables, dataSource);
    }

    public static String insert(OutboxSource source, String key, RecordStatus status, Instant createdAt) {
        return insert(source, key, "com.example.OrderCreated", "com.example.Handler#on(com.example.OrderCreated)",
                status, createdAt, status == RecordStatus.COMPLETED ? createdAt.plusMillis(250) : null,
                status == RecordStatus.FAILED ? 3 : 0, createdAt);
    }

    public static String insert(OutboxSource source, String key, String type, String handler, RecordStatus status,
                                Instant createdAt, Instant completedAt, int failureCount, Instant nextRetryAt) {
        String id = UUID.randomUUID().toString();
        source.jdbc()
                .sql("""
                        INSERT INTO %s (id, status, record_key, record_type, payload, context, partition_no, created_at,
                          completed_at, failure_count, failure_reason, next_retry_at, handler_id)
                        VALUES (:id, :status, :key, :type, '{"a":1}', '{"traceId":"t"}', :partition, :createdAt,
                          :completedAt, :failureCount, :reason, :nextRetryAt, :handler)""".formatted(source.tables().record()))
                .param("id", id)
                .param("status", status.name())
                .param("key", key)
                .param("type", type)
                .param("partition", Math.floorMod(key.hashCode(), 256))
                .param("createdAt", SqlTime.toTimestamp(createdAt))
                .param("completedAt", SqlTime.toTimestamp(completedAt))
                .param("failureCount", failureCount)
                .param("reason", status == RecordStatus.FAILED ? "boom" : null)
                .param("nextRetryAt", SqlTime.toTimestamp(nextRetryAt))
                .param("handler", handler)
                .update();
        return id;
    }
}
