package fr.pivot.agilite.poker.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the EN09.1 rate-limit AC against a real Redis instance and a
 * real WebSocket transport: a participant flooding a room past the 30 messages/second limit
 * ({@link PokerChannelInterceptor}) is warned, then force-disconnected after three consecutive
 * violations.
 *
 * <p>Deterministic unit-level coverage of the strike counter's state machine lives in
 * {@link PokerChannelInterceptorTest}; this class is the real-transport, real-Redis counterpart,
 * mirroring {@code WhiteboardRateLimitEnforcementIT} (EN08.1/US08.3.1,
 * {@code pivot-collaboratif-core}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PokerRateLimitEnforcementIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context.
     *
     * @param registry the dynamic property registry
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RoomAccessGrantService roomAccessGrantService;

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
     * Given a participant sending SEND frames well above the 30 messages/second limit, when
     * three consecutive frames are rate-limited, then the client receives an application-level
     * closure notification on {@code /user/queue/errors} and the underlying connection is
     * actually torn down by the server — {@link StompSession#isConnected()} becomes {@code
     * false} on its own, without the client ever calling {@code disconnect()}.
     */
    @Test
    void threeConsecutiveRateLimitViolationsActuallyCloseTheConnection() throws Exception {
        UUID roomId = UUID.randomUUID();
        String token = "token-" + UUID.randomUUID();
        roomAccessGrantService.grantAccess(roomId, token, Duration.ofMinutes(5));

        StompSession session = connect();
        CompletableFuture<String> closureErrorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/errors", new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) payload;
                String error = String.valueOf(body.get("error"));
                if (error.toLowerCase(Locale.ROOT).contains("closed") && !closureErrorFuture.isDone()) {
                    closureErrorFuture.complete(error);
                }
            }
        });

        // Brief pause so the broker registers the /user/queue/errors subscription before the
        // flood starts.
        Thread.sleep(200);

        String destination = PokerRoomDestinations.APP_ROOM_PREFIX + roomId + "/vote";
        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination(destination);
        sendHeaders.add(PokerChannelInterceptor.ACCESS_TOKEN_HEADER, token);

        // Flood well past the 30 msg/s limit in one burst — comfortably within the 1-second
        // fixed window, and comfortably past the 3rd consecutive violation needed to trigger
        // forced closure.
        for (int i = 0; i < 40 && session.isConnected(); i++) {
            session.send(sendHeaders, Map.of("vote", String.valueOf(i)));
        }

        String closureError = closureErrorFuture.get(8, TimeUnit.SECONDS);
        assertThat(closureError).containsIgnoringCase("closed");

        long deadline = System.currentTimeMillis() + 5000;
        while (session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.isConnected())
                .as("server must actually close the connection after 3 consecutive strikes, "
                        + "not merely announce it")
                .isFalse();
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
}
