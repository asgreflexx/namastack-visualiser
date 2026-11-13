package at.unleash.it.outboxvisualiser.web;

import static org.assertj.core.api.Assertions.assertThat;

import at.unleash.it.outboxvisualiser.record.Names;
import at.unleash.it.outboxvisualiser.record.OutboxRecordView;
import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.web.KeyTimeline.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyTimelineTest {

    private final Instant now = Instant.parse("2026-11-13T12:00:00Z");

    @Test
    void recordsBehindAnIncompleteRecordAreWaiting() {
        KeyTimeline timeline = KeyTimeline.of("order-1", List.of(
                record(RecordStatus.COMPLETED, 0, now.minusSeconds(100)),
                record(RecordStatus.FAILED, 10, now.minusSeconds(90)),
                record(RecordStatus.NEW, 20, now.minusSeconds(80)),
                record(RecordStatus.NEW, 30, now.minusSeconds(70))), now);

        assertThat(timeline.entries()).extracting(KeyTimeline.Entry::state)
                .containsExactly(State.DONE, State.FAILED, State.WAITING, State.WAITING);
        assertThat(timeline.waiting()).isEqualTo(2);
        assertThat(timeline.entries().getFirst().leftPercent()).isZero();
    }

    @Test
    void firstIncompleteRecordIsDueOrInBackoff() {
        assertThat(KeyTimeline.of("k", List.of(record(RecordStatus.NEW, 0, now.minusSeconds(1))), now)
                .entries().getFirst().state()).isEqualTo(State.DUE);
        assertThat(KeyTimeline.of("k", List.of(record(RecordStatus.NEW, 0, now.plusSeconds(30))), now)
                .entries().getFirst().state()).isEqualTo(State.BACKOFF);
    }

    @Test
    void shortensNamastackHandlerIds() {
        assertThat(Names.shortHandler("com.example.billing.InvoiceHandler#on(com.example.OrderCreated)")).isEqualTo("InvoiceHandler#on");
        assertThat(Names.shortHandler("invoice-handler")).isEqualTo("invoice-handler");
        assertThat(Names.simpleTypeName("com.example.Events$Created")).isEqualTo("Events$Created");
    }

    private OutboxRecordView record(RecordStatus status, int secondsAfterStart, Instant nextRetryAt) {
        Instant created = now.minusSeconds(100).plusSeconds(secondsAfterStart);
        return new OutboxRecordView("s", "id-" + secondsAfterStart, status, "order-1", "T", null, null, 0, created,
                status == RecordStatus.COMPLETED ? created.plusSeconds(1) : null, 0, null, nextRetryAt, "H");
    }
}
