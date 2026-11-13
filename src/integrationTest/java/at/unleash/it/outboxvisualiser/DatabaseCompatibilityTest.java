package at.unleash.it.outboxvisualiser;

import static at.unleash.it.outboxvisualiser.OutboxTestData.insert;
import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import at.unleash.it.outboxvisualiser.action.AuditLog;
import at.unleash.it.outboxvisualiser.action.RecordActionService;
import at.unleash.it.outboxvisualiser.cluster.ClusterRepository;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository.TimeColumn;
import at.unleash.it.outboxvisualiser.record.OutboxRecordView;
import at.unleash.it.outboxvisualiser.record.RecordFilter;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.SqlDialect;
import at.unleash.it.outboxvisualiser.source.TableNames;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties.TableProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Runs the visualiser's queries and actions against a real database that was set up with the
 * original Namastack schema script for that database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DatabaseCompatibilityTest {

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final OutboxRecordRepository repository = new OutboxRecordRepository();
    private final RecordActionService actions =
            new RecordActionService(Clock.fixed(now, ZoneOffset.UTC), new AuditLog());

    private JdbcDatabaseContainer<?> container;
    private OutboxSource source;

    protected abstract JdbcDatabaseContainer<?> createContainer();

    protected abstract String schemaScript();

    protected abstract SqlDialect expectedDialect();

    protected String scriptSeparator() {
        return ";";
    }

    @BeforeAll
    void startDatabase() {
        container = createContainer();
        container.start();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(container.getJdbcUrl());
        dataSource.setUsername(container.getUsername());
        dataSource.setPassword(container.getPassword());

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource(schemaScript()));
        populator.setSeparator(scriptSeparator());
        populator.execute(dataSource);

        source = new OutboxSource("it", "IT", false,
                TableNames.resolve(null, "", new TableProperties("outbox_record", "outbox_instance", "outbox_partition")),
                dataSource);
    }

    @AfterAll
    void stopDatabase() {
        if (source != null) {
            source.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void readsAndChangesRecords() {
        assertThat(source.dialect()).isEqualTo(expectedDialect());

        Instant t0 = now.minus(Duration.ofMinutes(10));
        String completed = insert(source, "order-1", RecordStatus.COMPLETED, t0);
        String failed = insert(source, "order-1", RecordStatus.FAILED, t0.plusSeconds(1));
        insert(source, "order-1", RecordStatus.NEW, t0.plusSeconds(2));
        insert(source, "order-2", RecordStatus.NEW, t0.plusSeconds(3));

        assertThat(repository.find(source, RecordFilter.none(), 0, 2).items()).hasSize(2);
        assertThat(repository.find(source, RecordFilter.none(), 1, 2).total()).isEqualTo(4);
        assertThat(repository.find(source, new RecordFilter(null, null, null, null, "order-", null, null), 0, 10).total()).isEqualTo(4);

        OutboxRecordView detail = repository.findById(source, completed).orElseThrow();
        assertThat(detail.createdAt()).isEqualTo(t0);
        assertThat(detail.payload()).isEqualTo("{\"a\":1}");

        assertThat(repository.blockedKeys(source, 10)).singleElement()
                .satisfies(key -> assertThat(key.waitingRecords()).isEqualTo(1));
        assertThat(repository.countPerBucket(source, TimeColumn.CREATED_AT, t0, Duration.ofMinutes(1), 3)).containsExactly(4, 0, 0);
        assertThat(repository.failuresByHandler(source, 5)).hasSize(1);
        assertThat(repository.openRecordsPerPartition(source)).isNotEmpty();
        assertThat(new ClusterRepository().findInstances(source)).isEmpty();

        assertThat(actions.retry(source, failed, "it").affected()).isEqualTo(1);
        assertThat(actions.retry(source, failed, "it").affected()).isZero();
        String copy = actions.replay(source, completed, "it").recordId();
        assertThat(repository.findById(source, copy).orElseThrow().payload()).isEqualTo("{\"a\":1}");
        assertThat(repository.findByKey(source, "order-1", 10).getLast().id()).isEqualTo(copy);
    }
}
