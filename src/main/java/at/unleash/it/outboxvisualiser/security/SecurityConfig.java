package at.unleash.it.outboxvisualiser.security;

import at.unleash.it.outboxvisualiser.source.VisualiserProperties;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties.Role;
import at.unleash.it.outboxvisualiser.source.VisualiserProperties.UserProperties;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Form login with users from {@code visualiser.users}. VIEWERs may look at everything, OPERATORs
 * may additionally call the write actions under {@code /actions/**}.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**", "/favicon.svg", "/actuator/health").permitAll()
                        .requestMatchers("/actions/**").hasRole(Role.OPERATOR.name())
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    InMemoryUserDetailsManager userDetailsService(VisualiserProperties properties) {
        List<UserProperties> configured = properties.users();
        if (configured.isEmpty()) {
            String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(12));
            log.warn("No users configured under 'visualiser.users'. Generated operator login: admin / {}", password);
            configured = List.of(new UserProperties("admin", "{noop}" + password, Role.OPERATOR));
        }
        List<UserDetails> users = new ArrayList<>();
        for (UserProperties user : configured) {
            String[] roles = user.role() == Role.OPERATOR
                    ? new String[] {Role.VIEWER.name(), Role.OPERATOR.name()}
                    : new String[] {Role.VIEWER.name()};
            users.add(User.withUsername(user.username())
                    .password(withEncodingId(user.password()))
                    .roles(roles)
                    .build());
        }
        return new InMemoryUserDetailsManager(users);
    }

    /** Plain passwords without an {@code {id}} prefix are accepted for local setups. */
    private static String withEncodingId(String password) {
        return password.startsWith("{") ? password : "{noop}" + password;
    }
}
