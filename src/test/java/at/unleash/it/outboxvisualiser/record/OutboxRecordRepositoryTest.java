package at.unleash.it.outboxvisualiser.record;

import static at.unleash.it.outboxvisualiser.OutboxTestData.insert;
import static org.assertj.core.api.Assertions.assertThat;

import at.unleash.it.outboxvisualiser.OutboxTestData;
import at.unleash.it.outboxvisualiser.record.OutboxRecordRepository.TimeColumn;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxRecordRepositoryTest {

    private final OutboxRecordRepository repository = new OutboxRecordRepository();
    private final Instant t0 = Instant.parse("2026-11-13T10:00:00Z");
    private OutboxSource source;

    @BeforeEach
    void setUp() {
        source = OutboxTestData.h2Source();
    }

    @Test
    void findsPagesNewestFirstWithFilters() {
        for (int i = 0; i < 7; i++) {
            insert(source, "order-" + i, RecordStatus.COMPLETED, t0.plusSeconds(i));
        }
        insert(source, "order-x", RecordStatus.FAILED, t0.plusSeconds(10));

        PageResult<OutboxRecordView> firstPage = repository.find(source, RecordFilter.none(), 0, 3);
        assertThat(firstPage.total()).isEqualTo(8);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(OutboxRecordView::recordKey).containsExactly("order-x", "order-6", "order-5");
        assertThat(firstPage.items().getFirst().payload()).as("list queries skip the payload").isNull();

        PageResult<OutboxRecordView> lastPage = repository.find(source, RecordFilter.none(), 2, 3);
        assertThat(lastPage.items()).extracting(OutboxRecordView::recordKey).containsExactly("order-1", "order-0");

        assertThat(repository.find(source, RecordFilter.ofStatus(RecordStatus.FAILED), 0, 10).items())
                .extracting(OutboxRecordView::recordKey).containsExactly("order-x");
    }

    @Test
    void keyFilterMatchesPrefixAndTreatsWildcardsLiterally() {
        insert(source, "order_1", RecordStatus.NEW, t0);
        insert(source, "orderX1", RecordStatus.NEW, t0);
        insert(source, "customer-1", RecordStatus.NEW, t0);

        RecordFilter filter = new RecordFilter(null, null, null, null, "order_", null, null);
        assertThat(repository.find(source, filter, 0, 10).items())
                .extracting(OutboxRecordView::recordKey).containsExactly("order_1");
    }

    @Test
    void loadsDetailsAndKeyInProcessingOrder() {
        String first = insert(source, "order-1", RecordStatus.COMPLETED, t0);
        String second = insert(source, "order-1", RecordStatus.FAILED, t0.plusSeconds(1));
        insert(source, "order-2", RecordStatus.NEW, t0.plusSeconds(2));

        OutboxRecordView detail = repository.findById(source, first).orElseThrow();
        assertThat(detail.payload()).isEqualTo("{\"a\":1}");
        assertThat(detail.createdAt()).isEqualTo(t0);
        assertThat(detail.processingTime()).isEqualTo(Duration.ofMillis(250));

        assertThat(repository.findByKey(source, "order-1", 100))
                .extracting(OutboxRecordView::id).containsExactly(first, second);
    }

    @Test
    void countsStatusesLagAndBlockedKeys() {
        insert(source, "order-1", RecordStatus.FAILED, t0);
        insert(source, "order-1", RecordStatus.NEW, t0.plusSeconds(1));
        insert(source, "order-1", RecordStatus.NEW, t0.plusSeconds(2));
        insert(source, "order-2", RecordStatus.NEW, t0.plusSeconds(3));
        insert(source, "order-3", RecordStatus.COMPLETED, t0);

        assertThat(repository.countByStatus(source))
                .containsEntry(RecordStatus.NEW, 3L)
                .containsEntry(RecordStatus.FAILED, 1L)
                .containsEntry(RecordStatus.COMPLETED, 1L);
        assertThat(repository.oldestCreatedAt(source, RecordStatus.NEW)).contains(t0.plusSeconds(1));
        assertThat(repository.countBlockedKeys(source)).isEqualTo(1);

        List<BlockedKey> blocked = repository.blockedKeys(source, 10);
        assertThat(blocked).singleElement().satisfies(key -> {
            assertThat(key.recordKey()).isEqualTo("order-1");
            assertThat(key.waitingRecords()).isEqualTo(2);
            assertThat(key.failureReason()).isEqualTo("boom");
        });
    }

    @Test
    void bucketsCreatedAndCompletedRecords() {
        Instant start = t0.truncatedTo(ChronoUnit.MINUTES);
        insert(source, "a", RecordStatus.COMPLETED, start.plusSeconds(5));
        insert(source, "b", RecordStatus.COMPLETED, start.plusSeconds(65));
        insert(source, "c", RecordStatus.NEW, start.plusSeconds(70));
        insert(source, "d", RecordStatus.NEW, start.plusSeconds(400));

        long[] created = repository.countPerBucket(source, TimeColumn.CREATED_AT, start, Duration.ofMinutes(1), 3);
        long[] completed = repository.countPerBucket(source, TimeColumn.COMPLETED_AT, start, Duration.ofMinutes(1), 3);
        assertThat(created).containsExactly(1, 2, 0);
        assertThat(completed).containsExactly(1, 1, 0);
    }

    @Test
    void groupsFailuresAndOpenRecordsPerPartition() {
        insert(source, "a", "T1", "H1#on(T1)", RecordStatus.FAILED, t0, null, 3, t0);
        insert(source, "b", "T1", "H1#on(T1)", RecordStatus.FAILED, t0, null, 3, t0);
        insert(source, "c", "T2", "H2#on(T2)", RecordStatus.FAILED, t0, null, 3, t0);

        assertThat(repository.failuresByHandler(source, 10))
                .containsExactly(new FailureGroup("H1#on(T1)", "T1", 2), new FailureGroup("H2#on(T2)", "T2", 1));
        assertThat(repository.openRecordsPerPartition(source).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(3);
        assertThat(repository.distinctHandlerIds(source)).containsExactly("H1#on(T1)", "H2#on(T2)");
    }
}
