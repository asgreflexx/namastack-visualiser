package at.unleash.it.outboxvisualiser;

import at.unleash.it.outboxvisualiser.source.SqlDialect;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

class SqlServerCompatibilityTest extends DatabaseCompatibilityTest {

    @Override
    protected JdbcDatabaseContainer<?> createContainer() {
        return new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
    }

    @Override
    protected String schemaScript() {
        return "schema/sqlserver.sql";
    }

    @Override
    protected SqlDialect expectedDialect() {
        return SqlDialect.SQLSERVER;
    }
}
