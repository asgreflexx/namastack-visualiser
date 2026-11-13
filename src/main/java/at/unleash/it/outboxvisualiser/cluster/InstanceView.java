package at.unleash.it.outboxvisualiser.cluster;

import java.time.Duration;
import java.time.Instant;

/** One row of the Namastack {@code outbox_instance} table. */
public record InstanceView(
        String instanceId,
        String hostname,
        int port,
        String status,
        Instant startedAt,
        Instant lastHeartbeat) {

    public Duration heartbeatAge(Instant now) {
        return Duration.between(lastHeartbeat, now);
    }

    /** ACTIVE with a recent heartbeat, ACTIVE but silent (stale), or anything else Namastack reports. */
    public Health health(Instant now, Duration staleAfter) {
        if (!"ACTIVE".equals(status)) {
            return "SHUTTING_DOWN".equals(status) ? Health.LEAVING : Health.DEAD;
        }
        return heartbeatAge(now).compareTo(staleAfter) > 0 ? Health.STALE : Health.ALIVE;
    }

    public enum Health {
        ALIVE,
        STALE,
        LEAVING,
        DEAD
    }
}
