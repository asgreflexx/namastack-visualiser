package at.unleash.it.outboxvisualiser.action;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records every write action. Entries go to the {@code AUDIT} logger, which can be routed to a
 * file or log shipper, and the most recent ones are kept in memory for the UI.
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final int CAPACITY = 500;

    private final Deque<Entry> entries = new ArrayDeque<>();

    public void record(Entry entry) {
        audit.info("user={} source={} action={} target={} affected={}",
                entry.user(), entry.sourceId(), entry.action(), entry.target(), entry.affected());
        synchronized (entries) {
            entries.addFirst(entry);
            if (entries.size() > CAPACITY) {
                entries.removeLast();
            }
        }
    }

    public List<Entry> recent() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public record Entry(Instant at, String user, String sourceId, RecordAction action, String target, int affected) {
    }
}
