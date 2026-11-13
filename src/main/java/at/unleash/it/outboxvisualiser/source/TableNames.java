package at.unleash.it.outboxvisualiser.source;

import java.util.regex.Pattern;

/**
 * Fully qualified names of the three Namastack tables of one source.
 *
 * <p>Table names cannot be bound as statement parameters, so every part is validated against a
 * strict identifier pattern before it is put into SQL.
 */
public record TableNames(String record, String instance, String partition) {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    public static TableNames resolve(String schema, String prefix, VisualiserProperties.TableProperties tables) {
        String safePrefix = prefix == null ? "" : prefix;
        return new TableNames(
                qualify(schema, safePrefix + tables.record()),
                qualify(schema, safePrefix + tables.instance()),
                qualify(schema, safePrefix + tables.partition()));
    }

    private static String qualify(String schema, String table) {
        requireIdentifier(table);
        if (schema == null || schema.isBlank()) {
            return table;
        }
        requireIdentifier(schema);
        return schema + "." + table;
    }

    private static void requireIdentifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: '" + value + "'");
        }
    }
}
