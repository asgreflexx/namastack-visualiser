package at.unleash.it.outboxvisualiser.demo;

import at.unleash.it.outboxvisualiser.record.RecordStatus;
import at.unleash.it.outboxvisualiser.source.OutboxSource;
import at.unleash.it.outboxvisualiser.source.OutboxSourceRegistry;
import at.unleash.it.outboxvisualiser.source.SqlTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Simulates a service using Namastack Outbox, so the visualiser can be tried without a real one.
 * It creates the original Namastack H2 schema in every configured source, seeds three hours of
 * history and then behaves like two Namastack instances: it writes new records, processes them per
 * key in order, retries with backoff and marks records FAILED after three attempts.
 */
@Component
@Profile("demo")
public class DemoDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoDataGenerator.class);

    private static final int PARTITIONS = 256;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration HISTORY = Duration.ofHours(3);
    private static final Duration RETENTION = Duration.ofHours(26);

    private static final List<EventKind> KINDS = List.of(
            new EventKind("com.example.order.OrderCreatedEvent",
                    "com.example.billing.InvoiceHandler#on(com.example.order.OrderCreatedEvent)", "order-", 0.01,
                    "java.lang.IllegalStateException: Tax rate for country 'XX' not configured"),
            new EventKind("com.example.order.OrderPaidEvent",
                    "com.example.shipping.ShippingLabelHandler#handle(com.example.order.OrderPaidEvent)", "order-", 0.25,
                    "org.springframework.web.client.ResourceAccessException: I/O error on POST request for \"https://carrier.example.com/labels\": Read timed out"),
            new EventKind("com.example.order.OrderCancelledEvent",
                    "com.example.billing.RefundHandler#handle(com.example.order.OrderCancelledEvent)", "order-", 0.05,
                    "com.example.billing.PaymentProviderException: 409 Conflict - refund already in progress"),
            new EventKind("com.example.customer.CustomerRegisteredEvent",
                    "com.example.notification.WelcomeMailHandler#send(com.example.customer.CustomerRegisteredEvent)", "customer-", 0.03,
                    "org.springframework.mail.MailSendException: Mail server connection failed"));

    private final List<OutboxSource> sources;
    private final Clock clock;

    public DemoDataGenerator(OutboxSourceRegistry registry, Clock clock) {
        this.sources = registry.all();
        this.clock = clock;
        sources.forEach(this::initialise);
    }

    private void initialise(OutboxSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("demo/schema-h2.sql")).execute(source.dataSource());
        Instant now = clock.instant();
        String table = source.tables().instance();
        insertInstance(source, table, "demo-a", "orders-7f9c-a", 8080, "ACTIVE", now.minus(HISTORY), now);
        insertInstance(source, table, "demo-b", "orders-7f9c-b", 8080, "ACTIVE", now.minus(Duration.ofMinutes(95)), now);
        insertInstance(source, table, "demo-c", "orders-6d21-x", 8080, "DEAD", now.minus(Duration.ofHours(5)), now.minus(Duration.ofMinutes(96)));
        for (int partition = 0; partition < PARTITIONS; partition++) {
            source.jdbc()
                    .sql("INSERT INTO " + source.tables().partition()
                            + " (partition_number, instance_id, version, updated_at) VALUES (:p, :i, 1, :t)")
                    .param("p", partition)
                    .param("i", partition < PARTITIONS / 2 ? "demo-a" : "demo-b")
                    .param("t", SqlTime.toTimestamp(now.minus(Duration.ofMinutes(95))))
                    .update();
        }
        seedHistory(source, now);
        log.info("Demo data created in source '{}'", source.id());
    }

    private void seedHistory(OutboxSource source, Instant now) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Set<String> blockedKeys = new HashSet<>();
        Instant at = now.minus(HISTORY);
        while (at.isBefore(now.minusSeconds(30))) {
            double minuteOfHistory = Duration.between(now.minus(HISTORY), at).toMinutes();
            double rate = 1.2 + Math.sin(minuteOfHistory / 25.0) * 0.8; // records per second, varying
            at = at.plusMillis((long) (1000 / rate * random.nextDouble(0.3, 1.7)));

            EventKind kind = KINDS.get(random.nextInt(KINDS.size()));
            String key = kind.keyPrefix() + (1000 + random.nextInt(600));
            if (blockedKeys.contains(key)) {
                insert(source, kind, key, at, RecordStatus.NEW, null, 0, null, at);
            } else if (blockedKeys.size() < 6 && random.nextDouble() < kind.failureRate() / 30) {
                blockedKeys.add(key);
                insert(source, kind, key, at, RecordStatus.FAILED, null, MAX_ATTEMPTS, kind.failureReason(), at.plusSeconds(35));
            } else {
                Instant completed = at.plusMillis(random.nextLong(40, kind.failureRate() > 0.1 ? 4_000 : 900));
                insert(source, kind, key, at, RecordStatus.COMPLETED, completed, 0, null, at);
            }
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void tick() {
        for (OutboxSource source : sources) {
            try {
                Instant now = clock.instant();
                produce(source, now);
                process(source, now);
                heartbeat(source, now);
            } catch (RuntimeException e) {
                log.warn("Demo tick failed for source '{}': {}", source.id(), e.getMessage());
            }
        }
    }

    private void produce(OutboxSource source, Instant now) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double rate = 1.2 + Math.sin(now.getEpochSecond() / 600.0) * 0.8;
        int count = (int) Math.round(rate * random.nextDouble(0.2, 1.8));
        for (int i = 0; i < count; i++) {
            EventKind kind = KINDS.get(random.nextInt(KINDS.size()));
            String key = kind.keyPrefix() + (1000 + random.nextInt(600));
            insert(source, kind, key, now, RecordStatus.NEW, null, 0, null, now);
        }
    }

    /** Processes the first incomplete record of every key if it is NEW and due, like Namastack does. */
    private void process(OutboxSource source, Instant now) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String table = source.tables().record();
        List<Pending> candidates = source.jdbc()
                .sql("""
                        SELECT o.id, o.record_type, o.failure_count, o.created_at FROM %1$s o
                        WHERE o.status = 'NEW' AND o.next_retry_at <= :now AND o.created_at <= :minAge
                          AND NOT EXISTS (SELECT 1 FROM %1$s e WHERE e.record_key = o.record_key AND e.completed_at IS NULL
                                          AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id)))
                        ORDER BY o.created_at, o.id""".formatted(table))
                .param("now", SqlTime.toTimestamp(now))
                .param("minAge", SqlTime.toTimestamp(now.minusMillis(1500)))
                .query((rs, rowNum) -> new Pending(rs.getString("id"), rs.getString("record_type"),
                        rs.getInt("failure_count"), SqlTime.instant(rs, "created_at")))
                .list();

        for (Pending pending : candidates) {
            EventKind kind = KINDS.stream().filter(k -> k.recordType().equals(pending.recordType())).findFirst().orElse(KINDS.getFirst());
            if (random.nextDouble() >= kind.failureRate()) {
                source.jdbc()
                        .sql("UPDATE " + table + " SET status = 'COMPLETED', completed_at = :now WHERE id = :id AND status = 'NEW'")
                        .param("now", SqlTime.toTimestamp(now))
                        .param("id", pending.id())
                        .update();
                continue;
            }
            int attempts = pending.failureCount() + 1;
            boolean exhausted = attempts >= MAX_ATTEMPTS;
            source.jdbc()
                    .sql("UPDATE " + table + " " + """
                             SET status = :status, failure_count = :attempts, failure_reason = :reason, next_retry_at = :next
                             WHERE id = :id AND status = 'NEW'""")
                    .param("status", exhausted ? RecordStatus.FAILED.name() : RecordStatus.NEW.name())
                    .param("attempts", attempts)
                    .param("reason", kind.failureReason())
                    .param("next", SqlTime.toTimestamp(now.plusSeconds(5L << attempts)))
                    .param("id", pending.id())
                    .update();
        }

        source.jdbc()
                .sql("DELETE FROM " + table + " WHERE status = 'COMPLETED' AND completed_at < :cutoff")
                .param("cutoff", SqlTime.toTimestamp(now.minus(RETENTION)))
                .update();
    }

    private void heartbeat(OutboxSource source, Instant now) {
        source.jdbc()
                .sql("UPDATE " + source.tables().instance() + " SET last_heartbeat = :now, updated_at = :now WHERE status = 'ACTIVE'")
                .param("now", SqlTime.toTimestamp(now))
                .update();
    }

    private static void insert(OutboxSource source, EventKind kind, String key, Instant createdAt, RecordStatus status,
                               Instant completedAt, int failureCount, String failureReason, Instant nextRetryAt) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String payload = """
                {"%s":"%s","amount":%d.%02d,"currency":"EUR","items":%d,"occurredAt":"%s"}"""
                .formatted(kind.keyPrefix().startsWith("order") ? "orderId" : "customerId", key,
                        random.nextInt(10, 900), random.nextInt(100), random.nextInt(1, 6), createdAt);
        String context = """
                {"traceId":"%s","tenant":"at-vienna"}""".formatted(UUID.randomUUID().toString().replace("-", ""));
        source.jdbc()
                .sql("INSERT INTO " + source.tables().record() + """
                         (id, status, record_key, record_type, payload, context, partition_no, created_at, completed_at,
                          failure_count, failure_reason, next_retry_at, handler_id)
                        VALUES (:id, :status, :key, :type, :payload, :context, :partition, :createdAt, :completedAt,
                          :failureCount, :failureReason, :nextRetryAt, :handler)""")
                .param("id", uuidV7(createdAt))
                .param("status", status.name())
                .param("key", key)
                .param("type", kind.recordType())
                .param("payload", payload)
                .param("context", context)
                .param("partition", Math.floorMod(key.hashCode(), PARTITIONS))
                .param("createdAt", SqlTime.toTimestamp(createdAt))
                .param("completedAt", SqlTime.toTimestamp(completedAt))
                .param("failureCount", failureCount)
                .param("failureReason", failureReason)
                .param("nextRetryAt", SqlTime.toTimestamp(nextRetryAt))
                .param("handler", kind.handlerId())
                .update();
    }

    private static void insertInstance(OutboxSource source, String table, String id, String host, int port, String status,
                                       Instant startedAt, Instant heartbeat) {
        source.jdbc()
                .sql("INSERT INTO " + table + """
                         (instance_id, hostname, port, status, started_at, last_heartbeat, created_at, updated_at)
                        VALUES (:id, :host, :port, :status, :startedAt, :heartbeat, :startedAt, :heartbeat)""")
                .param("id", id)
                .param("host", host)
                .param("port", port)
                .param("status", status)
                .param("startedAt", SqlTime.toTimestamp(startedAt))
                .param("heartbeat", SqlTime.toTimestamp(heartbeat))
                .update();
    }

    private static String uuidV7(Instant at) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (at.toEpochMilli() << 16) | (0x7L << 12) | random.nextInt(1 << 12);
        long lsb = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private record EventKind(String recordType, String handlerId, String keyPrefix, double failureRate, String failureReason) {
    }

    private record Pending(String id, String recordType, int failureCount, Instant createdAt) {
    }
}
