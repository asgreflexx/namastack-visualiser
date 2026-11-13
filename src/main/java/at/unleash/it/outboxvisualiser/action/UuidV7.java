package at.unleash.it.outboxvisualiser.action;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** UUID version 7 (RFC 9562), the id format Namastack uses for outbox records. */
final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    static UUID generate(Clock clock) {
        long millis = clock.millis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
