package at.unleash.it.outboxvisualiser.source;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Converts time values the same way Namastack's JDBC module does ({@link Timestamp} in both
 * directions), so values written by Namastack are read back unchanged on every database.
 */
public final class SqlTime {

    private SqlTime() {
    }

    public static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
