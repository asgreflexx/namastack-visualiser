package at.unleash.it.outboxvisualiser;

import at.unleash.it.outboxvisualiser.source.SqlDialect;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;

class MariaDbCompatibilityTest extends DatabaseCompatibilityTest {

    @Override
    protected JdbcDatabaseContainer<?> createContainer() {
        return new MariaDBContainer("mariadb:11.4");
    }

    @Override
    protected String schemaScript() {
        return "schema/mariadb.sql";
    }

    @Override
    protected SqlDialect expectedDialect() {
        return SqlDialect.MARIADB;
    }
}
