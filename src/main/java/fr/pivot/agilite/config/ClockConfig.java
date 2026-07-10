package fr.pivot.agilite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the shared {@link Clock} bean (US09.1.1) — Spring Boot does not register one by
 * default. Using an injected {@link Clock} rather than {@code Instant.now()} directly lets
 * {@code PokerRoomServiceTest} assert exact {@code expiresAt} values with a fixed clock.
 */
@Configuration
public class ClockConfig {

    /**
     * The system UTC clock used everywhere a room timestamp is computed.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
