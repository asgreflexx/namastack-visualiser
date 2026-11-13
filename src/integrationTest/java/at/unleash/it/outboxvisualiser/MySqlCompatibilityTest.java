package at.unleash.it.outboxvisualiser;

import at.unleash.it.outboxvisualiser.source.SqlDialect;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;

class MySqlCompatibilityTest extends DatabaseCompatibilityTest {

    @Override
    protected JdbcDatabaseContainer<?> createContainer() {
        return new MySQLContainer("mysql:8.4");
    }

    @Override
    protected String schemaScript() {
        return "schema/mysql.sql";
    }

    @Override
    protected SqlDialect expectedDialect() {
        return SqlDialect.MYSQL;
    }
}
