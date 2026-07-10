package fr.pivot.agilite.config;

import fr.pivot.agilite.poker.ws.PokerChannelInterceptor;
import fr.pivot.agilite.retro.ws.RetroChannelInterceptor;
import fr.pivot.agilite.wheel.ws.WheelChannelInterceptor;
import fr.pivot.agilite.ws.WsConnectionHandshakeHandler;
import fr.pivot.agilite.ws.WsSessionTrackingHandlerDecoratorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP broker relay configuration for the Agilite domain (EN07.3 — ActiveMQ persistence
 * KahaDB, multi-repo) plus, additively, the EN09.1 planning-poker room broker.
 *
 * <p>Relays STOMP traffic to the shared ActiveMQ broker (owned and configured by
 * {@code pivot-core}: KahaDB persistence, {@code DLQ.agilite}, memory/store limits, internal-
 * only console) instead of an in-process broker. This is the cross-module-core event bus.
 *
 * <p><strong>Why a WebSocket endpoint is registered here too:</strong> Spring's {@code
 * @EnableWebSocketMessageBroker} infrastructure ({@code SubProtocolWebSocketHandler}) requires
 * at least one registered STOMP endpoint to start at all — with zero endpoints the application
 * context fails to refresh ({@code IllegalStateException: No handlers}), verified empirically
 * while building this class. A minimal endpoint is therefore unavoidable plumbing to have the
 * relay itself exist, not scope creep: this module's own stack table already anticipates
 * browser realtime collaboration on capacity planning/standup ({@code CLAUDE.md}), so this only
 * moves that already-planned plumbing slightly earlier.
 *
 * <p><strong>Security — no bearer-token authentication yet (deliberate, documented gap):</strong>
 * this repo has no {@code SecurityConfig} and no opaque-token validation at all yet — {@code
 * fr.pivot:pivot-core-starter} is not published/consumable (see {@code CLAUDE.md}). The
 * {@code /ws/agilite} endpoint therefore assigns every connection an anonymous, server-generated
 * {@link fr.pivot.agilite.ws.WsConnectionPrincipal} (see {@link WsConnectionHandshakeHandler}) —
 * a correlation handle for error-notification addressing only, carrying no user/tenant claim.
 * EN09.1's room isolation is deliberately built without depending on a trusted client identity
 * at all (see {@link PokerChannelInterceptor}'s and {@code RoomAccessGrantService}'s JavaDoc) —
 * this is not a workaround for the missing auth, it is the intended long-term shape for
 * ephemeral, code-joined rooms. Real bearer-token identity (mirroring pivot-core's
 * {@code StompAuthChannelInterceptor} pattern once the starter is consumable) remains a hard
 * prerequisite for any future feature that needs to know *which authenticated user* sent a
 * message (e.g. attributing a vote) — not for this Enabler.
 *
 * <p><strong>Domain isolation ({@code /topic/agilite.} prefix, EN07.3):</strong> {@link
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
 * <p><strong>Destination naming — dot, not slash, for the EN07.3 relay only:</strong> the
 * backlog AC describes topics as {@code /topic/agilite/**} (prose intent: "all topics under
 * this domain"). The actual destinations relayed here are dot-separated after the prefix (e.g.
 * {@code /topic/agilite.capacity-updated}), because ActiveMQ's wildcard destination matching —
 * used broker-side for the {@code DLQ.agilite} dead-letter policy ({@code topic="agilite.>"}) —
 * only matches dot-delimited hierarchy segments. A slash-based destination becomes one opaque
 * segment to that matcher and would never match the wildcard.
 *
 * <p><strong>EN09.1 — planning-poker room broker (additive, disjoint prefix):</strong> room
 * traffic ({@code /topic/agilite/poker/{roomId}}, slash-separated — see
 * {@code fr.pivot.agilite.poker.ws.PokerRoomDestinations}) is registered on its own in-process
 * {@code SimpleBroker}, not relayed through ActiveMQ: this is ephemeral, single-instance,
 * low-latency room pub/sub, with no need for the shared durable cross-domain bus, exactly
 * mirroring {@code pivot-collaboratif-core}'s split between {@code /topic/whiteboard/*}
 * (SimpleBroker, per-board rooms, EN08.1) and {@code /topic/collaboratif.*} (StompBrokerRelay,
 * cross-domain bus, EN07.3-equivalent). Spring supports a {@code SimpleBroker} and a
 * {@code StompBrokerRelay} registered simultaneously as long as their prefixes are disjoint —
 * confirmed here: {@code /topic/agilite/poker} (slash immediately after {@code agilite}) can
 * never {@code startsWith}-match {@code /topic/agilite.} (dot immediately after {@code
 * agilite}), and vice versa. {@code /queue} is registered alongside it for
 * {@code /user/queue/errors} SUBSCRIBE/SEND-denial notifications (see
 * {@link PokerChannelInterceptor}). {@link PokerChannelInterceptor} enforces room-scoped
 * authorization and rate limiting on this traffic; {@link WsSessionTrackingHandlerDecoratorFactory}
 * lets it force-close a session after repeated violations.
 *
 * <p><strong>US20.1.2a — retro session broker (same in-process {@code SimpleBroker}, additional
 * disjoint prefix):</strong> {@code /topic/agilite/retro/{sessionId}} (see {@code
 * fr.pivot.agilite.retro.ws.RetroSessionDestinations}) is registered on the exact same {@code
 * SimpleBroker} instance as planning-poker rooms, not a separate one — both are ephemeral,
 * single-instance, low-latency pub/sub with no need for the durable cross-domain bus, and {@code
 * enableSimpleBroker} accepts any number of disjoint prefixes in one registration. {@link
 * RetroChannelInterceptor} enforces the same session-scoped authorization/rate-limiting pattern
 * as {@link PokerChannelInterceptor}, registered alongside it on the same inbound channel.
 *
 * <p><strong>US14.3.1 — wheel broker (same in-process {@code SimpleBroker}, additional disjoint
 * prefix):</strong> {@code /topic/agilite/wheels/{wheelId}} (see {@code
 * fr.pivot.agilite.wheel.ws.WheelDestinations}) is registered on the exact same {@code
 * SimpleBroker} instance as planning-poker rooms and retro sessions — same "ephemeral,
 * single-instance, no need for the durable cross-domain bus" rationale. {@link
 * WheelChannelInterceptor} enforces subscription authorization, but — unlike poker/retro —
 * against the caller's real bearer-token identity and the wheel's own team-membership check
 * (reused from {@code WheelService}), not an opaque session grant: a wheel belongs to a
 * permanent team, not an ad hoc invite-code-joined room/session. No rate limiting is registered
 * for this prefix — there is no client-to-server application destination for wheels to abuse
 * (see {@link WheelChannelInterceptor}'s JavaDoc).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP destination prefix relayed by this module's EN07.3 ActiveMQ relay — see the class-
     * level JavaDoc for the isolation and naming rationale. Package-private for direct assertion
     * from tests.
     */
    static final String DOMAIN_TOPIC_PREFIX = "/topic/agilite.";

    /**
     * STOMP destination prefixes served by the EN09.1 in-process room broker — see the class-
     * level JavaDoc's "planning-poker room broker" section for why this is a separate broker
     * registration from {@link #DOMAIN_TOPIC_PREFIX}. Package-private for direct assertion from
     * tests.
     */
    static final String[] ROOM_BROKER_PREFIXES =
            {"/topic/agilite/poker", "/topic/agilite/retro", "/topic/agilite/wheels", "/queue"};

    private final String relayHost;
    private final int relayPort;
    private final boolean relayEnabled;
    private final String allowedOrigins;
    private final PokerChannelInterceptor pokerChannelInterceptor;
    private final RetroChannelInterceptor retroChannelInterceptor;
    private final WheelChannelInterceptor wheelChannelInterceptor;
    private final WsSessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory;

    /**
     * Creates the configuration with the shared broker's connection coordinates and the EN09.1/
     * US20.1.2a room-isolation collaborators.
     *
     * @param relayHost                              hostname of the shared ActiveMQ broker
     *                                                (STOMP transport)
     * @param relayPort                              STOMP port of the shared ActiveMQ broker
     * @param relayEnabled                           whether to register the EN07.3 broker relay
     *                                                at all — {@code false} in the {@code test}
     *                                                profile (see {@link #configureMessageBroker}
     *                                                JavaDoc for why)
     * @param allowedOrigins                         CORS-allowed origins for the WebSocket
     *                                                handshake
     * @param pokerChannelInterceptor                STOMP frame interceptor enforcing planning-
     *                                                poker room access grants and rate limits
     * @param retroChannelInterceptor                STOMP frame interceptor enforcing retro
     *                                                session access grants and rate limits
     *                                                (US20.1.2a)
     * @param wheelChannelInterceptor                 STOMP frame interceptor enforcing wheel
     *                                                subscription authorization (US14.3.1)
     * @param sessionTrackingHandlerDecoratorFactory  decorator factory that feeds
     *                                                {@code WsSessionRegistry}, used by
     *                                                {@code pokerChannelInterceptor}/{@code
     *                                                retroChannelInterceptor} to force-close a
     *                                                session after repeated rate-limit violations
     */
    public WebSocketConfig(
            @Value("${pivot.activemq.relay-host}") final String relayHost,
            @Value("${pivot.activemq.relay-port}") final int relayPort,
            @Value("${pivot.activemq.relay-enabled:true}") final boolean relayEnabled,
            @Value("${pivot.cors.allowed-origins}") final String allowedOrigins,
            final PokerChannelInterceptor pokerChannelInterceptor,
            final RetroChannelInterceptor retroChannelInterceptor,
            final WheelChannelInterceptor wheelChannelInterceptor,
            final WsSessionTrackingHandlerDecoratorFactory sessionTrackingHandlerDecoratorFactory) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.relayEnabled = relayEnabled;
        this.allowedOrigins = allowedOrigins;
        this.pokerChannelInterceptor = pokerChannelInterceptor;
        this.retroChannelInterceptor = retroChannelInterceptor;
        this.wheelChannelInterceptor = wheelChannelInterceptor;
        this.sessionTrackingHandlerDecoratorFactory = sessionTrackingHandlerDecoratorFactory;
    }

    /**
     * Configures the STOMP broker relay (EN07.3) scoped to this module's domain prefix, the new
     * EN09.1 in-process room broker (disjoint prefix, see class JavaDoc), and the application
     * destination prefix for {@code @MessageMapping} handlers.
     *
     * <p><strong>{@code pivot.activemq.relay-enabled} toggle (EN09.1 addition):</strong> before
     * this Enabler, this class registered only the EN07.3 relay, so its (documented, pre-
     * existing) unreachability in test environments with no ActiveMQ Testcontainer was harmless
     * in isolation. Adding a second, simultaneous broker registration for room traffic changes
     * that: {@code pivot-collaboratif-core} empirically found that a {@code StompBrokerRelay}
     * whose target is unreachable does not fail in isolation — its repeated connection failures
     * were observed to cascade into {@code ConnectionLostException}s on that module's
     * {@code SimpleBroker} WebSocket sessions too, even though the two registrations handle
     * disjoint prefixes. Gating relay registration behind this flag (default {@code true};
     * {@code false} in {@code application-test.yml}) avoids reproducing that failure mode for
     * every test exercising EN09.1 room traffic; {@code WebSocketConfigIT} (the one test that
     * does care about the relay) overrides it back to {@code true} via
     * {@code @DynamicPropertySource}, against a real Testcontainers broker.
     *
     * @param registry the message broker registry to configure
     */
    @Override
    public void configureMessageBroker(final MessageBrokerRegistry registry) {
        if (relayEnabled) {
            registry.enableStompBrokerRelay(DOMAIN_TOPIC_PREFIX)
                    .setRelayHost(relayHost)
                    .setRelayPort(relayPort)
                    .setSystemHeartbeatSendInterval(10000)
                    .setSystemHeartbeatReceiveInterval(10000);
        }

        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-room-heartbeat-");
        heartbeatScheduler.initialize();
        registry.enableSimpleBroker(ROOM_BROKER_PREFIXES)
                .setHeartbeatValue(new long[]{25000L, 30000L})
                .setTaskScheduler(heartbeatScheduler);

        registry.setApplicationDestinationPrefixes("/app/agilite");
    }

    /**
     * Registers the WebSocket endpoint required for the broker relay infrastructure to start
     * (see the class-level JavaDoc), with the anonymous {@link WsConnectionHandshakeHandler}
     * (EN09.1) assigning every connection a correlation identity for error-notification
     * addressing.
     *
     * @param registry the STOMP endpoint registry
     */
    @Override
    public void registerStompEndpoints(final StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/agilite")
                .setHandshakeHandler(new WsConnectionHandshakeHandler())
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    /**
     * Registers {@link WsSessionTrackingHandlerDecoratorFactory} so sessions can later be
     * force-closed by ID (EN09.1 rate-limit strike enforcement, see
     * {@link PokerChannelInterceptor}).
     *
     * @param registration the transport registration to configure
     */
    @Override
    public void configureWebSocketTransport(final WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionTrackingHandlerDecoratorFactory);
    }

    /**
     * Registers {@link PokerChannelInterceptor}, {@link RetroChannelInterceptor} and
     * {@link WheelChannelInterceptor} on the client inbound channel, enforcing EN09.1/US20.1.2a/
     * US14.3.1 isolation on every SUBSCRIBE (and, for poker/retro, SEND) frame. Each interceptor
     * only ever acts on its own domain's destination prefixes (see their respective JavaDoc) —
     * registering all three has no effect on one another's traffic.
     *
     * @param registration the inbound channel registration
     */
    @Override
    public void configureClientInboundChannel(final ChannelRegistration registration) {
        registration.interceptors(pokerChannelInterceptor, retroChannelInterceptor, wheelChannelInterceptor);
    }
}
