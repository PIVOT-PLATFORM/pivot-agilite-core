package fr.pivot.agilite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP broker relay configuration for the Agilite domain (EN07.3 — ActiveMQ persistence
 * KahaDB, multi-repo).
 *
 * <p>Relays STOMP traffic to the shared ActiveMQ broker (owned and configured by
 * {@code pivot-core}: KahaDB persistence, {@code DLQ.agilite}, memory/store limits, internal-
 * only console) instead of an in-process broker. This is the cross-module-core event bus —
 * <b>not</b> a browser-facing realtime channel yet (see {@link #registerStompEndpoints}).
 *
 * <p>Registering zero STOMP endpoints does not prevent the application context from starting:
 * verified empirically against the real {@code PivotAgiliteApplication} (embedded Tomcat, real
 * datasource/Redis) — {@code @EnableWebSocketMessageBroker}'s infrastructure boots fine with no
 * registered endpoint, the relay's "system" session simply attempts its TCP connection in the
 * background regardless.
 *
 * <p><strong>Domain isolation ({@code /topic/agilite.} prefix):</strong> {@link
 * MessageBrokerRegistry#enableStompBrokerRelay(String...)} only relays messages whose
 * destination starts with one of the given prefixes — anything else is silently not
 * forwarded by this JVM's relay handler (see {@code
 * org.springframework.messaging.simp.AbstractBrokerMessageHandler#checkDestinationPrefix}).
 * Scoping this module to {@code /topic/agilite.} (trailing dot) means this application can
 * never relay another domain's traffic (pilotage, collaboratif), even by accident — this is
 * the enforced isolation boundary for this Enabler's AC, applied independently in each
 * module-core. Broker-side ACL (rejecting a connection that tries to (re)subscribe to another
 * domain's topic at the transport level) is a documented, accepted follow-up gap, not built
 * here — consistent with this codebase's existing practice of flagging known gaps rather than
 * over-building (see e.g. {@code pivot-core/docker-compose.prod.yml}'s note on unauthenticated
 * Redis).
 *
 * <p><strong>Destination naming — dot, not slash:</strong> the backlog AC describes topics as
 * {@code /topic/agilite/**} (prose intent: "all topics under this domain"). The actual
 * destinations used here are dot-separated after the prefix (e.g. {@code
 * /topic/agilite.capacity-updated}), because ActiveMQ's wildcard destination matching — used
 * broker-side for the {@code DLQ.agilite} dead-letter policy ({@code topic="agilite.>"}) —
 * only matches dot-delimited hierarchy segments. A slash-based destination becomes one opaque
 * segment to that matcher and would never match the wildcard.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP destination prefix relayed by this module — see the class-level JavaDoc for the
     * isolation and naming rationale. Package-private for direct assertion from tests.
     */
    static final String DOMAIN_TOPIC_PREFIX = "/topic/agilite.";

    private final String relayHost;
    private final int relayPort;

    /**
     * Creates the configuration with the shared broker's connection coordinates.
     *
     * @param relayHost hostname of the shared ActiveMQ broker (STOMP transport)
     * @param relayPort STOMP port of the shared ActiveMQ broker
     */
    public WebSocketConfig(
            @Value("${pivot.activemq.relay-host}") final String relayHost,
            @Value("${pivot.activemq.relay-port}") final int relayPort) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
    }

    /**
     * Configures the STOMP broker relay, scoped to this module's domain prefix, and the
     * application destination prefix for future {@code @MessageMapping} handlers.
     *
     * @param registry the message broker registry to configure
     */
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        registry.enableStompBrokerRelay(DOMAIN_TOPIC_PREFIX)
                .setRelayHost(relayHost)
                .setRelayPort(relayPort)
                .setSystemHeartbeatSendInterval(10000)
                .setSystemHeartbeatReceiveInterval(10000);
        registry.setApplicationDestinationPrefixes("/app/agilite");
    }

    /**
     * Intentionally registers no browser-facing STOMP endpoint yet. EN07.3 only wires the
     * relay to the shared broker (the cross-module-core event bus); Spring establishes the
     * relay's "system" TCP connection to the broker at context startup regardless of whether
     * any WebSocket endpoint is registered, which is what proves this configuration works.
     * The browser-facing endpoint (SockJS fallback, STOMP CONNECT authentication once {@code
     * pivot-core-starter} is consumable) lands with the first realtime US that needs it — not
     * fabricated ahead of time, consistent with this repo's bootstrap-only status.
     *
     * @param registry the STOMP endpoint registry (unused — no endpoint registered)
     */
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        // No endpoint yet — see method JavaDoc.
    }
}
