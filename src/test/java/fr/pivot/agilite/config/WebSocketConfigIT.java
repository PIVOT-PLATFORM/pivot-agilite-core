package fr.pivot.agilite.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@link WebSocketConfig}'s STOMP broker relay actually connects to
 * a real ActiveMQ broker.
 *
 * <p>Only {@link WebSocketConfig} is loaded (no {@code @SpringBootApplication} component scan)
 * — this relay has no dependency on the datasource/Redis/Flyway infrastructure the full
 * application context would otherwise require, so this stays fast and focused.
 *
 * <p>{@link BrokerAvailabilityEvent} is the idiomatic Spring signal for STOMP broker relay
 * connectivity: it is published {@code true} once the relay's "system" TCP connection to the
 * broker succeeds, and {@code false} on disconnect — asserting it is the minimal, correct
 * proof that this configuration reaches a real broker, without needing a full pub/sub
 * round-trip.
 */
@Testcontainers
@SpringBootTest(
        classes = {WebSocketConfig.class, WebSocketConfigIT.BrokerAvailabilityCaptureConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WebSocketConfigIT {

    @Container
    static final GenericContainer<?> activemq =
            new GenericContainer<>(DockerImageName.parse("apache/activemq-classic:6.2.0"))
                    .withExposedPorts(61613, 8161);

    @DynamicPropertySource
    static void activemqProperties(final DynamicPropertyRegistry registry) {
        registry.add("pivot.activemq.relay-host", activemq::getHost);
        registry.add("pivot.activemq.relay-port", () -> activemq.getMappedPort(61613));
    }

    @Autowired
    private AtomicBoolean brokerAvailable;

    /** Maximum time to wait for the relay's "system" connection to report the broker available. */
    private static final long AVAILABILITY_TIMEOUT_MS = 15_000L;

    /** Polling interval while waiting for {@link #brokerAvailable} to flip. */
    private static final long POLL_INTERVAL_MS = 100L;

    /**
     * Given this module's STOMP relay configuration, when the application context starts
     * against a real ActiveMQ broker, then a {@link BrokerAvailabilityEvent} signalling a
     * successful connection is published within a reasonable timeout.
     *
     * @throws InterruptedException if interrupted while polling for the availability flag
     */
    @Test
    void relayConnectsToRealBroker() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AVAILABILITY_TIMEOUT_MS;
        while (!brokerAvailable.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertThat(brokerAvailable).isTrue();
    }

    /** Captures {@link BrokerAvailabilityEvent} into a plain flag observable by the test. */
    @Configuration
    static class BrokerAvailabilityCaptureConfig {

        /**
         * Flag flipped to {@code true} the moment the relay reports the broker as available.
         *
         * @return a fresh flag for each test context
         */
        @Bean
        AtomicBoolean brokerAvailable() {
            return new AtomicBoolean(false);
        }

        /**
         * Listens for {@link BrokerAvailabilityEvent} and updates the shared flag.
         *
         * @param brokerAvailable the flag to update
         * @return the listener bean
         */
        @Bean
        ApplicationListener<BrokerAvailabilityEvent> brokerAvailabilityListener(final AtomicBoolean brokerAvailable) {
            return event -> brokerAvailable.set(event.isBrokerAvailable());
        }
    }
}
