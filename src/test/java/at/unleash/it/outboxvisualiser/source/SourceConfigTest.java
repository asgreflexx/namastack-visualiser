package at.unleash.it.outboxvisualiser.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.unleash.it.outboxvisualiser.source.VisualiserProperties.TableProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SourceConfigTest {

    private final TableProperties defaults = new TableProperties("outbox_record", "outbox_instance", "outbox_partition");

    @ParameterizedTest
    @CsvSource({
            "MySQL, MYSQL",
            "MariaDB, MARIADB",
            "Oracle, ORACLE",
            "Microsoft SQL Server, SQLSERVER",
            "H2, H2",
            "PostgreSQL, GENERIC"
    })
    void detectsDialectFromProductName(String productName, SqlDialect expected) {
        assertThat(SqlDialect.fromProductName(productName)).isEqualTo(expected);
    }

    @Test
    void paginatesPerDialect() {
        assertThat(SqlDialect.MYSQL.paginate("SELECT 1 ORDER BY x")).endsWith("LIMIT :limit OFFSET :offset");
        assertThat(SqlDialect.ORACLE.paginate("SELECT 1 ORDER BY x")).endsWith("OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
        assertThat(SqlDialect.SQLSERVER.paginate("SELECT 1 ORDER BY x")).endsWith("OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY");
    }

    @Test
    void resolvesTableNamesLikeNamastack() {
        assertThat(TableNames.resolve(null, "", defaults).record()).isEqualTo("outbox_record");
        assertThat(TableNames.resolve("orders", "svc_", defaults))
                .isEqualTo(new TableNames("orders.svc_outbox_record", "orders.svc_outbox_instance", "orders.svc_outbox_partition"));
    }

    @Test
    void rejectsIdentifiersThatCouldInjectSql() {
        assertThatThrownBy(() -> TableNames.resolve("x; DROP TABLE y", "", defaults)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TableNames.resolve(null, "a b", defaults)).isInstanceOf(IllegalArgumentException.class);
    }
}
