package at.unleash.it.outboxvisualiser.source;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Holds one {@link OutboxSource} with its own small connection pool per configured database.
 */
@Component
public class OutboxSourceRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxSourceRegistry.class);

    private final Map<String, OutboxSource> sources = new LinkedHashMap<>();

    public OutboxSourceRegistry(VisualiserProperties properties) {
        for (VisualiserProperties.SourceProperties config : properties.sources()) {
            if (sources.containsKey(config.id())) {
                throw new IllegalStateException("Duplicate outbox source id '" + config.id() + "'");
            }
            TableNames tables = TableNames.resolve(config.schema(), config.tablePrefix(), config.tables());
            sources.put(config.id(), new OutboxSource(
                    config.id(), config.displayName(), config.readOnly(), tables, createDataSource(config)));
            log.info("Registered outbox source '{}' ({}) with record table {}", config.id(), config.url(), tables.record());
        }
        if (sources.isEmpty()) {
            log.warn("No outbox sources configured. Add entries under 'visualiser.sources' or start with profile 'demo'.");
        }
    }

    private static HikariDataSource createDataSource(VisualiserProperties.SourceProperties config) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("outbox-" + config.id());
        dataSource.setJdbcUrl(config.url());
        dataSource.setUsername(config.username());
        dataSource.setPassword(config.password());
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5_000);
        // Do not fail startup when a database is down; the dashboard reports it instead.
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    public List<OutboxSource> all() {
        return List.copyOf(sources.values());
    }

    public OutboxSource get(String id) {
        OutboxSource source = sources.get(id);
        if (source == null) {
            throw new NoSuchElementException("Unknown outbox source '" + id + "'");
        }
        return source;
    }

    /** Returns the requested source, or all sources when {@code id} is empty. */
    public List<OutboxSource> select(String id) {
        return id == null || id.isBlank() ? all() : List.of(get(id));
    }

    @Override
    public void destroy() {
        sources.values().forEach(OutboxSource::close);
    }
}
