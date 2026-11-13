package at.unleash.it.outboxvisualiser;

import at.unleash.it.outboxvisualiser.source.SqlDialect;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.oracle.OracleContainer;

class OracleCompatibilityTest extends DatabaseCompatibilityTest {

    @Override
    protected JdbcDatabaseContainer<?> createContainer() {
        return new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
    }

    @Override
    protected String schemaScript() {
        return "schema/oracle.sql";
    }

    /** The Namastack Oracle script consists of PL/SQL blocks terminated by a slash. */
    @Override
    protected String scriptSeparator() {
        return "/";
    }

    @Override
    protected SqlDialect expectedDialect() {
        return SqlDialect.ORACLE;
    }
}
