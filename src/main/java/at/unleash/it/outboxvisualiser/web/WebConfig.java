package at.unleash.it.outboxvisualiser.web;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
