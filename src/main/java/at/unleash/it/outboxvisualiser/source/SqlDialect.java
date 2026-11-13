package at.unleash.it.outboxvisualiser.source;

import java.util.Locale;

/**
 * The few SQL differences between the supported databases. Everything else is written in portable
 * SQL, and all time values are bound as parameters, so no date functions are needed.
 */
public enum SqlDialect {
    H2,
    MYSQL,
    MARIADB,
    ORACLE,
    SQLSERVER,
    GENERIC;

    public static SqlDialect fromProductName(String productName) {
        String name = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (name.contains("mariadb")) {
            return MARIADB;
        }
        if (name.contains("mysql")) {
            return MYSQL;
        }
        if (name.contains("oracle")) {
            return ORACLE;
        }
        if (name.contains("sql server")) {
            return SQLSERVER;
        }
        if (name.contains("h2")) {
            return H2;
        }
        return GENERIC;
    }

    /**
     * Appends a row-limit clause using the named parameters {@code :limit} and {@code :offset}. The
     * statement must end with an {@code ORDER BY}, which SQL Server requires for {@code OFFSET}.
     */
    public String paginate(String sql) {
        return switch (this) {
            case MYSQL, MARIADB -> sql + " LIMIT :limit OFFSET :offset";
            case H2, ORACLE, SQLSERVER, GENERIC -> sql + " OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY";
        };
    }
}
