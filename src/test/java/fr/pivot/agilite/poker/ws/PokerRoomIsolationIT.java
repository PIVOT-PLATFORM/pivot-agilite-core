package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Integration tests for EN09.1 — WebSocket room isolation for planning poker, against a real
 * WebSocket transport, a real Redis-backed {@link RoomAccessGrantService}, and a real (if
 * schema-only) Postgres instance (required for the full Spring context to start; no poker
 * entity exists yet — see {@link RoomAccessGrantService}'s JavaDoc).
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A client presenting a currently valid room access grant can subscribe to that room's
 *       topic and receives broadcasts sent to it.</li>
 *   <li>A client presenting no access token, or an unknown one, is silently denied — the
 *       subscription is never established, and an error notification is delivered on
 *       {@code /user/queue/errors}.</li>
 *   <li>A token granted for one room never authorizes subscribing to a different room
 *       (cross-room isolation).</li>
 *   <li>A denied SUBSCRIBE does not close the WebSocket session — a subsequent, correctly
 *       authorized subscription on the very same session still succeeds.</li>
 * </ol>
 *
 * <p>The join flow that would normally call {@link RoomAccessGrantService#grantAccess} (US09.1.2)
 * does not exist yet (parallel, not a dependency of this Enabler — see
 * {@code pivot-docs/docs/backlog/sprints/sprint-8.md}); grants are issued directly via the
 * autowired service, mirroring how {@code WhiteboardWebSocketIT} seeds board membership directly
 * via JPA repositories "to avoid HTTP layer coupling" before the equivalent US existed there.
 *
 * <p>Rate-limit/force-close behavior against a real transport is covered separately by
 * {@code PokerRateLimitEnforcementIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PokerRoomIsolationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context, and seeds the
     * {@code public} schema (owned by {@code pivot-core}, not by this repo's own Flyway) before
     * Flyway runs — required since US20.1.1 added FK references from
     * {@code agilite.retro_sessions} into {@code public.tenants}/{@code public.teams}/
     * {@code public.users}, which now makes the {@code agilite} migration fail on any fresh
     * Testcontainers Postgres that doesn't already have those tables (this class's own poker/ws
     * feature never touches them directly, but the shared migration file does).
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        PlatformAuthTestSupport.createPublicSchema(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RoomAccessGrantService roomAccessGrantService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final List<StompSession> openSessions = new ArrayList<>();

    /** Disconnects all open STOMP sessions after each test. */
    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    /**
     * Given a currently valid room access grant, when the client subscribes to that room's
     * topic presenting the grant's token, then it receives a broadcast subsequently sent to
     * that topic.
     */
    @Test
    void subscriberWithValidGrantReceivesRoomBroadcast() throws Exception {
        UUID roomId = UUID.randomUUID();
        String token = "token-" + UUID.randomUUID();
        roomAccessGrantService.grantAccess(roomId, token, Duration.ofMinutes(5));
        StompSession session = connect();

        CompletableFuture<Map<String, Object>> received = subscribeRoom(session, roomId, token);
        awaitSubscriptionEstablished();

        broadcastReveal(PokerRoomDestinations.roomTopic(roomId));

        Map<String, Object> payload = received.get(5, TimeUnit.SECONDS);
        assertThat(payload).containsEntry("type", "REVEAL");
    }

    /**
     * Given no access token presented at all, when the client attempts to subscribe to a room's
     * topic, then the subscription is denied: no broadcast is ever received, an error
     * notification is delivered on {@code /user/queue/errors}, and the session itself stays
     * open.
     */
    @Test
    void subscriberWithoutAccessTokenIsDeniedAndNotified() throws Exception {
        UUID roomId = UUID.randomUUID();
        roomAccessGrantService.grantAccess(roomId, "the-real-token", Duration.ofMinutes(5));
        StompSession session = connect();
        CompletableFuture<String> errorFuture = subscribeErrors(session);

        CompletableFuture<Map<String, Object>> received = subscribeRoom(session, roomId, null);
        awaitSubscriptionEstablished();
        broadcastReveal(PokerRoomDestinations.roomTopic(roomId));

        String error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error).contains("Access denied");
        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS));
        assertThat(session.isConnected()).isTrue();
    }

    /**
     * Security AC (cross-room isolation): given a grant issued for room A, when the client
     * subscribes to room B's topic presenting room A's token, then the subscription is denied
     * and no broadcast sent to room B is ever received.
     */
    @Test
    void tokenGrantedForOneRoomDoesNotAuthorizeAnotherRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        String tokenForRoomA = "token-" + UUID.randomUUID();
        roomAccessGrantService.grantAccess(roomA, tokenForRoomA, Duration.ofMinutes(5));
        StompSession session = connect();

        CompletableFuture<Map<String, Object>> received = subscribeRoom(session, roomB, tokenForRoomA);
        awaitSubscriptionEstablished();
        broadcastReveal(PokerRoomDestinations.roomTopic(roomB));

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS));
    }

    /**
     * Given a denied SUBSCRIBE on a session, when the same session subsequently subscribes to a
     * room it does hold a valid grant for, then that second subscription succeeds — a denial
     * must never close the session or otherwise impair its other, legitimate subscriptions.
     */
    @Test
    void deniedSubscribeDoesNotAffectSubsequentValidSubscriptionOnSameSession() throws Exception {
        UUID deniedRoom = UUID.randomUUID();
        UUID grantedRoom = UUID.randomUUID();
        String token = "token-" + UUID.randomUUID();
        roomAccessGrantService.grantAccess(grantedRoom, token, Duration.ofMinutes(5));
        StompSession session = connect();

        subscribeRoom(session, deniedRoom, "unknown-token");
        awaitSubscriptionEstablished();

        assertThat(session.isConnected()).isTrue();

        CompletableFuture<Map<String, Object>> received = subscribeRoom(session, grantedRoom, token);
        awaitSubscriptionEstablished();
        broadcastReveal(PokerRoomDestinations.roomTopic(grantedRoom));

        Map<String, Object> payload = received.get(5, TimeUnit.SECONDS);
        assertThat(payload).containsEntry("type", "REVEAL");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Connects to the anonymous WebSocket endpoint, blocking until the STOMP session is
     * established or timing out after 5 seconds.
     *
     * @return an open STOMP session
     */
    private StompSession connect() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());

        String url = "ws://localhost:" + port + "/api/agilite/ws/agilite";
        StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Subscribes to a room's topic, presenting the given access token as a native header (if
     * non-{@code null}), and returns a future completed with the first payload received.
     *
     * @param session     the STOMP session
     * @param roomId      the room to subscribe to
     * @param accessToken the access token to present, or {@code null} to present none
     * @return a future completed with the first received payload
     */
    private CompletableFuture<Map<String, Object>> subscribeRoom(
            final StompSession session, final UUID roomId, final String accessToken) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(PokerRoomDestinations.roomTopic(roomId));
        if (accessToken != null) {
            headers.add(PokerChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        }
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders stompHeaders) {
                return Map.class;
            }

            @Override
            public void handleFrame(final StompHeaders stompHeaders, final Object payload) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) payload;
                future.complete(body);
            }
        });
        return future;
    }

    /**
     * Subscribes to the session's own {@code /user/queue/errors} and returns a future completed
     * with the first error message text received.
     *
     * @param session the STOMP session
     * @return a future completed with the first error string
     */
    private CompletableFuture<String> subscribeErrors(final StompSession session) {
        CompletableFuture<String> future = new CompletableFuture<>();
        session.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) payload;
                future.complete(String.valueOf(body.get("error")));
            }
        });
        return future;
    }

    /**
     * Brief pause so the broker has registered the just-issued SUBSCRIBE before any broadcast is
     * sent — otherwise an early broadcast could race the subscription's registration.
     */
    private void awaitSubscriptionEstablished() throws InterruptedException {
        Thread.sleep(200);
    }

    /**
     * Broadcasts a minimal {@code REVEAL}-type payload to the given topic, simulating what a
     * future poker feature (e.g. US09.2.2) would eventually publish.
     *
     * <p>The payload is explicitly cast to {@link Object}: {@link SimpMessagingTemplate}
     * overloads {@code convertAndSend} with both {@code (destination, payload)} and
     * {@code (payload, headers)} variants — passing a {@code Map<String, Object>} literal
     * directly is ambiguous between them, since it structurally matches both. The cast forces
     * resolution to the destination/payload overload.
     *
     * @param topic the topic destination to broadcast to
     */
    private void broadcastReveal(final String topic) {
        messagingTemplate.convertAndSend(topic, (Object) Map.of("type", "REVEAL"));
    }
}
