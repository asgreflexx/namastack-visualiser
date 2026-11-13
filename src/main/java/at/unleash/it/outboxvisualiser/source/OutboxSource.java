package at.unleash.it.outboxvisualiser.source;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

/**
 * One configured database containing Namastack outbox tables.
 *
 * <p>The dialect is detected lazily on first use, so an unreachable database does not prevent the
 * application from starting; it shows up as an error on the dashboard instead.
 */
public final class OutboxSource implements AutoCloseable {

    private final String id;
    private final String name;
    private final boolean readOnly;
    private final TableNames tables;
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private volatile SqlDialect dialect;

    public OutboxSource(String id, String name, boolean readOnly, TableNames tables, DataSource dataSource) {
        this.id = id;
        this.name = name;
        this.readOnly = readOnly;
        this.tables = tables;
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public TableNames tables() {
        return tables;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public SqlDialect dialect() {
        SqlDialect current = dialect;
        if (current == null) {
            try {
                String product = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName);
                current = SqlDialect.fromProductName(product);
            } catch (MetaDataAccessException e) {
                throw new IllegalStateException("Cannot connect to outbox source '" + id + "': " + e.getMessage(), e);
            }
            dialect = current;
        }
        return current;
    }

    @Override
    public void close() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
