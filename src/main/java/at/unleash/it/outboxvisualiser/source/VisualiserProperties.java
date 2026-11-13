package at.unleash.it.outboxvisualiser.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the visualiser: the outbox sources (one per service database) and the users
 * that may log in.
 */
@Validated
@ConfigurationProperties("visualiser")
public record VisualiserProperties(
        List<@Valid SourceProperties> sources,
        List<@Valid UserProperties> users,
        @DefaultValue("30s") Duration instanceStaleAfter) {

    public VisualiserProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * One database that contains Namastack outbox tables.
     *
     * @param id          stable identifier used in URLs
     * @param name        display name, defaults to the id
     * @param url         JDBC URL
     * @param schema      optional schema, like {@code namastack.outbox.jdbc.schema-name}
     * @param tablePrefix optional prefix, like {@code namastack.outbox.jdbc.table-prefix}
     * @param tables      base table names, like {@code namastack.outbox.jdbc.table-names}
     * @param readOnly    disables all write actions for this source
     */
    public record SourceProperties(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]*") String id,
            String name,
            @NotBlank String url,
            String username,
            String password,
            String schema,
            @DefaultValue("") String tablePrefix,
            @DefaultValue TableProperties tables,
            boolean readOnly) {

        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }
    }

    public record TableProperties(
            @DefaultValue("outbox_record") String record,
            @DefaultValue("outbox_instance") String instance,
            @DefaultValue("outbox_partition") String partition) {
    }

    public record UserProperties(
            @NotBlank String username,
            @NotBlank String password,
            @DefaultValue("VIEWER") Role role) {
    }

    public enum Role {
        /** May look at everything but cannot change records. */
        VIEWER,
        /** May additionally retry, reschedule and replay records. */
        OPERATOR
    }
}
