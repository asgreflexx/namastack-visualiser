package at.unleash.it.outboxvisualiser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Visualises the Namastack Outbox tables of one or more services and allows operators to retry
 * or replay records. The application has no database of its own: every configured source is an
 * external database that contains the Namastack outbox tables.
 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration"
})
@ConfigurationPropertiesScan
public class OutboxVisualiserApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutboxVisualiserApplication.class, args);
    }
}
