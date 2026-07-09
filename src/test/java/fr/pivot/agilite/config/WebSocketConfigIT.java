package fr.pivot.agilite.config;

import fr.pivot.agilite.poker.ws.PokerChannelInterceptor;
import fr.pivot.agilite.ws.WsSessionRegistry;
import fr.pivot.agilite.ws.WsSessionTrackingHandlerDecoratorFactory;
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
import static org.mockito.Mockito.mock;

/**
 * Integration test proving {@link WebSocketConfig}'s STOMP broker relay actually connects to
 * a real ActiveMQ broker.
 *
 * <p>Only {@link WebSocketConfig} is loaded (no {@code @SpringBootApplication} component scan)
 * — this relay has no dependency on the datasource/Redis/Flyway infrastructure the full
 * application context would otherwise require, so this stays fast and focused. This requires
 * {@link WebSocketConfig#registerStompEndpoints} to register at least one real endpoint (see
 * its class JavaDoc) — a slice loaded with zero endpoints was verified to fail with
 * {@code IllegalStateException: No handlers}.
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
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "pivot.cors.allowed-origins=http://localhost:4200")
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

    /**
     * Captures {@link BrokerAvailabilityEvent} into a plain flag observable by the test, and
     * supplies mocked EN09.1 collaborators so this slice context (only {@link WebSocketConfig}
     * plus this configuration) can satisfy {@link WebSocketConfig}'s constructor without pulling
     * in the full poker room-isolation stack (Redis, grant store) — irrelevant to what this test
     * proves (the ActiveMQ relay connects).
     */
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
         * Mocked EN09.1 room-isolation interceptor — unused by this relay-connectivity test.
         *
         * @return a Mockito mock
         */
        @Bean
        PokerChannelInterceptor pokerChannelInterceptor() {
            return mock(PokerChannelInterceptor.class);
        }

        /**
         * A <strong>real</strong> (not mocked) session-tracking decorator factory, backed by a
         * real {@link WsSessionRegistry} — Spring's own STOMP endpoint infrastructure invokes
         * {@code decorate(handler)} while building {@code stompWebSocketHandlerMapping} even
         * under {@code WebEnvironment.NONE}; a Mockito mock's unstubbed {@code decorate} returns
         * {@code null} by default, which breaks that infrastructure with "WebSocketHandler is
         * required" (verified empirically). Both collaborators are cheap, dependency-free POJOs,
         * so using the real thing is simpler than stubbing the mock to behave correctly anyway.
         *
         * @return a real decorator factory backed by a fresh registry
         */
        @Bean
        WsSessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory() {
            return new WsSessionTrackingHandlerDecoratorFactory(new WsSessionRegistry());
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
