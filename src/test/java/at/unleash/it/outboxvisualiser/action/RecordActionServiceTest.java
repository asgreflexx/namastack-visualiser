package at.unleash.it.outboxvisualiser.action;

import static at.unleash.it.outboxvisualiser.OutboxTestData.insert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.unleash.it.outboxvisualiser.OutboxTestData;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository;
import at.unleash.it.outboxvisualiser.record.OutboxRecordView;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordActionServiceTest {

    private final Instant now = Instant.parse("2026-11-13T12:00:00Z");
    private final Instant earlier = now.minusSeconds(600);
    private final AuditLog auditLog = new AuditLog();
    private final RecordActionService actions = new RecordActionService(Clock.fixed(now, ZoneOffset.UTC), auditLog);
    private final OutboxRecordRepository repository = new OutboxRecordRepository();
    private OutboxSource source;

    @BeforeEach
    void setUp() {
        source = OutboxTestData.h2Source();
    }

    @Test
    void retryResetsFailedRecordAndIsGuardedByStatus() {
        String id = insert(source, "order-1", RecordStatus.FAILED, earlier);

        ActionResult first = actions.retry(source, id, "alice");
        ActionResult second = actions.retry(source, id, "alice");

        assertThat(first.affected()).isEqualTo(1);
        assertThat(second.affected()).as("record is no longer FAILED").isZero();
        OutboxRecordView record = repository.findById(source, id).orElseThrow();
        assertThat(record.status()).isEqualTo(RecordStatus.NEW);
        assertThat(record.failureCount()).isZero();
        assertThat(record.failureReason()).isNull();
        assertThat(record.nextRetryAt()).isEqualTo(now);
        assertThat(repository.countBlockedKeys(source)).isZero();
        assertThat(auditLog.recent()).hasSize(2).first().satisfies(entry -> {
            assertThat(entry.user()).isEqualTo("alice");
            assertThat(entry.action()).isEqualTo(RecordAction.RETRY);
        });
    }

    @Test
    void runNowOnlyTouchesNewRecords() {
        String waiting = insert(source, "order-1", "T", "H", RecordStatus.NEW, earlier, null, 1, now.plusSeconds(300));
        String done = insert(source, "order-2", RecordStatus.COMPLETED, earlier);

        assertThat(actions.runNow(source, waiting, "alice").affected()).isEqualTo(1);
        assertThat(actions.runNow(source, done, "alice").affected()).isZero();
        assertThat(repository.findById(source, waiting).orElseThrow().nextRetryAt()).isEqualTo(now);
    }

    @Test
    void replayCopiesCompletedRecordBehindExistingRecordsOfItsKey() {
        String original = insert(source, "order-1", RecordStatus.COMPLETED, earlier);
        insert(source, "order-1", RecordStatus.COMPLETED, earlier.plusSeconds(1));

        ActionResult result = actions.replay(source, original, "alice");

        assertThat(result.affected()).isEqualTo(1);
        assertThat(result.recordId()).isNotEqualTo(original);
        assertThat(result.recordId().charAt(14)).as("UUID version 7").isEqualTo('7');
        OutboxRecordView copy = repository.findById(source, result.recordId()).orElseThrow();
        OutboxRecordView source0 = repository.findById(source, original).orElseThrow();
        assertThat(copy.status()).isEqualTo(RecordStatus.NEW);
        assertThat(copy.createdAt()).isEqualTo(now);
        assertThat(copy.completedAt()).isNull();
        assertThat(copy.payload()).isEqualTo(source0.payload());
        assertThat(copy.context()).isEqualTo(source0.context());
        assertThat(copy.handlerId()).isEqualTo(source0.handlerId());
        assertThat(copy.partitionNo()).isEqualTo(source0.partitionNo());
        assertThat(source0.status()).as("original stays untouched").isEqualTo(RecordStatus.COMPLETED);

        List<OutboxRecordView> key = repository.findByKey(source, "order-1", 10);
        assertThat(key.getLast().id()).isEqualTo(copy.id());
    }

    @Test
    void replayRefusesRecordsThatAreNotCompleted() {
        String failed = insert(source, "order-1", RecordStatus.FAILED, earlier);
        assertThat(actions.replay(source, failed, "alice").affected()).isZero();
        assertThat(repository.findByKey(source, "order-1", 10)).hasSize(1);
    }

    @Test
    void bulkRetryRespectsHandlerAndTypeFilter() {
        insert(source, "a", "T1", "H1", RecordStatus.FAILED, earlier, null, 3, earlier);
        insert(source, "b", "T1", "H1", RecordStatus.FAILED, earlier, null, 3, earlier);
        insert(source, "c", "T2", "H2", RecordStatus.FAILED, earlier, null, 3, earlier);

        assertThat(actions.countFailed(source, "H1", "T1")).isEqualTo(2);
        assertThat(actions.bulkRetry(source, "H1", "T1", "alice").affected()).isEqualTo(2);
        assertThat(actions.countFailed(source, null, null)).isEqualTo(1);
    }

    @Test
    void readOnlySourcesRejectWrites() {
        OutboxSource writable = OutboxTestData.h2Source();
        OutboxSource readOnly = new OutboxSource("ro", "RO", true, writable.tables(), writable.dataSource());
        String id = insert(readOnly, "order-1", RecordStatus.FAILED, earlier);

        assertThatThrownBy(() -> actions.retry(readOnly, id, "alice")).isInstanceOf(ReadOnlySourceException.class);
        assertThat(repository.findById(readOnly, id).orElseThrow().status()).isEqualTo(RecordStatus.FAILED);
    }
}
