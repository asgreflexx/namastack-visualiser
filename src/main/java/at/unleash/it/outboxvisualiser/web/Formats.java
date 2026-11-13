package at.unleash.it.outboxvisualiser.web;

import at.unleash.it.outboxvisualiser.record.Names;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Formatting helpers for templates, used as {@code ${@fmt.time(instant)}}. */
@Component("fmt")
public class Formats {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private static final DateTimeFormatter PRECISE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS");

    private final Clock clock;
    private final ZoneId zone = ZoneId.systemDefault();

    public Formats(Clock clock) {
        this.clock = clock;
    }

    public String time(Instant instant) {
        return instant == null ? "–" : TIME.format(instant.atZone(zone));
    }

    public String precise(Instant instant) {
        return instant == null ? "–" : PRECISE.format(instant.atZone(zone));
    }

    /** "vor 3 min" or "in 20 s". */
    public String relative(Instant instant) {
        if (instant == null) {
            return "–";
        }
        Duration diff = Duration.between(clock.instant(), instant);
        return diff.isNegative() ? "vor " + duration(diff.negated()) : "in " + duration(diff);
    }

    public String duration(Duration duration) {
        if (duration == null) {
            return "–";
        }
        long millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + " ms";
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3_600) {
            return (seconds / 60) + " min " + (seconds % 60) + " s";
        }
        if (seconds < 86_400) {
            return (seconds / 3_600) + " h " + (seconds % 3_600 / 60) + " min";
        }
        return (seconds / 86_400) + " d " + (seconds % 86_400 / 3_600) + " h";
    }

    public String number(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value).replace(',', '.');
    }

    public boolean isPast(Instant instant) {
        return instant != null && !instant.isAfter(clock.instant());
    }

    public String type(String recordType) {
        return Names.simpleTypeName(recordType);
    }

    public String handler(String handlerId) {
        return Names.shortHandler(handlerId);
    }

    public String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
