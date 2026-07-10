package fr.pivot.agilite.wheel.ws;

import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for US14.3.1 — WebSocket subscription isolation and real-time broadcast for
 * wheels, against a real WebSocket transport, a real (Testcontainers) PostgreSQL instance, and a
 * real {@code POST /wheels/{wheelId}/spin} triggering the broadcast under test.
 *
 * <p>Mirrors {@code PokerRoomIsolationIT}'s structure (EN09.1 precedent for testing WS
 * authorization), but the authorization mechanism itself is different by design (see {@link
 * WheelChannelInterceptor}'s JavaDoc): real bearer tokens + team membership, not an opaque
 * per-room grant — so fixtures here seed real {@code public.users}/{@code
 * public.team_members}/{@code public.access_tokens} rows via {@link PlatformAuthTestSupport}
 * rather than calling a grant service directly.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>A team member subscribes successfully and receives the real broadcast triggered by a
 *       {@code POST /spin} performed by a different member of the same team.</li>
 *   <li>A caller from a different team of the <em>same</em> tenant is denied.</li>
 *   <li>A caller from a <em>different tenant</em> entirely is denied.</li>
 *   <li>A caller presenting no {@code Authorization} header at all is denied.</li>
 *   <li>Every denial is silent (no session close) and notified only on
 *       {@code /user/queue/errors} — a subsequent, correctly authorized subscription on the same
 *       session still succeeds.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WheelWsIsolationIT {

    private static final String BASE_PATH = "/wheels";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies Testcontainer-derived connection properties to the Spring context, and seeds the
     * {@code public} schema (owned by {@code pivot-core}) before Flyway runs — see {@code
     * PokerRoomIsolationIT}/{@code WheelSpinControllerIT} for the identical rationale.
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
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
     * Given a user who is a member of the wheel's owning team, when they subscribe to the
     * wheel's topic and a different member of the same team performs a real {@code POST /spin},
     * then the subscriber receives the exact broadcast contract.
     */
    @Test
    void teamMemberSubscribesAndReceivesRealSpinBroadcast() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        long tenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, "team-spin");
        long spinnerUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        long audienceUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, spinnerUserId);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, audienceUserId);
        String spinnerToken = PlatformAuthTestSupport.issueToken(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), spinnerUserId, "active", Instant.now().plusSeconds(3600));
        String audienceToken = PlatformAuthTestSupport.issueToken(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), audienceUserId, "active", Instant.now().plusSeconds(3600));

        String wheelId = createWheelWithEntries(spinnerToken, teamId, "Roue audience",
                "[{\"type\": \"free_text\", \"label\": \"Solo\"}]");

        StompSession session = connect();
        CompletableFuture<Map<String, Object>> received = subscribeWheel(session, wheelId, audienceToken);
        awaitSubscriptionEstablished();

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + spinnerToken))
                .andExpect(status().isCreated());

        Map<String, Object> payload = received.get(5, TimeUnit.SECONDS);
        assertThat(payload).containsEntry("wheelId", wheelId);
        assertThat(payload).containsEntry("label", "Solo");
        assertThat(payload).containsEntry("antiRepeatMode", "reduced_weight");
        assertThat(payload).containsKey("entryId");
        assertThat(payload).containsKey("drawnAt");
    }

    /**
     * Security AC: given a caller who is authenticated but belongs to a *different* team of the
     * *same* tenant as the wheel's owning team, when they attempt to subscribe, then the
     * subscription is denied — no broadcast is ever received, and an error notification is
     * delivered on {@code /user/queue/errors}.
     */
    @Test
    void nonMemberOfWheelsTeamSubscriptionIsDenied() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        long tenantId = PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
        long ownerTeamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, "team-owner");
        long otherTeamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, "team-other");
        long ownerUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        long outsiderUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), ownerTeamId, ownerUserId);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), otherTeamId, outsiderUserId);
        String ownerToken = PlatformAuthTestSupport.issueToken(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), ownerUserId, "active", Instant.now().plusSeconds(3600));
        String outsiderToken = PlatformAuthTestSupport.issueToken(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), outsiderUserId, "active", Instant.now().plusSeconds(3600));

        String wheelId = createWheelWithEntries(ownerToken, ownerTeamId, "Roue privee",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        StompSession session = connect();
        CompletableFuture<String> errorFuture = subscribeErrors(session);
        CompletableFuture<Map<String, Object>> received = subscribeWheel(session, wheelId, outsiderToken);
        awaitSubscriptionEstablished();

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isCreated());

        String error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error).contains("Access denied");
        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS));
        assertThat(session.isConnected()).isTrue();
    }

    /**
     * Security AC: given a caller authenticated in a *different tenant* entirely, when they
     * attempt to subscribe to a wheel belonging to another tenant, then the subscription is
     * denied exactly like the cross-team case — same anti-enumeration convention as the REST
     * 404s of US14.1.1/US14.2.1.
     */
    @Test
    void crossTenantCallerSubscriptionIsDenied() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        AuthFixture ownerFixture = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        AuthFixture otherTenantFixture = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        String wheelId = createWheelWithEntries(ownerFixture.rawToken(), ownerFixture.teamId(), "Roue tenant A",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        StompSession session = connect();
        CompletableFuture<Map<String, Object>> received =
                subscribeWheel(session, wheelId, otherTenantFixture.rawToken());
        awaitSubscriptionEstablished();

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + ownerFixture.rawToken()))
                .andExpect(status().isCreated());

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS));
    }

    /**
     * Given a client presenting no {@code Authorization} header at all, when it attempts to
     * subscribe to a wheel's topic, then the subscription is denied.
     */
    @Test
    void missingAuthorizationHeaderSubscriptionIsDenied() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        AuthFixture ownerFixture = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        String wheelId = createWheelWithEntries(ownerFixture.rawToken(), ownerFixture.teamId(), "Roue sans token",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        StompSession session = connect();
        CompletableFuture<String> errorFuture = subscribeErrors(session);
        CompletableFuture<Map<String, Object>> received = subscribeWheel(session, wheelId, null);
        awaitSubscriptionEstablished();

        String error = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(error).contains("Access denied");
        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> received.get(500, TimeUnit.MILLISECONDS));
    }

    /**
     * Given a denied SUBSCRIBE on a session, when the same session subsequently subscribes to a
     * wheel it does have real access to, then that second subscription succeeds — a denial must
     * never close the session or otherwise impair its other, legitimate subscriptions (mirrors
     * {@code PokerRoomIsolationIT}'s identical guarantee).
     */
    @Test
    void deniedSubscribeDoesNotAffectSubsequentValidSubscriptionOnSameSession() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        AuthFixture ownerFixture = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        AuthFixture otherTenantFixture = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        String deniedWheelId = createWheelWithEntries(ownerFixture.rawToken(), ownerFixture.teamId(), "Roue refusee",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");
        String grantedWheelId = createWheelWithEntries(ownerFixture.rawToken(), ownerFixture.teamId(), "Roue permise",
                "[{\"type\": \"free_text\", \"label\": \"B\"}]");

        StompSession session = connect();
        subscribeWheel(session, deniedWheelId, otherTenantFixture.rawToken());
        awaitSubscriptionEstablished();
        assertThat(session.isConnected()).isTrue();

        CompletableFuture<Map<String, Object>> received =
                subscribeWheel(session, grantedWheelId, ownerFixture.rawToken());
        awaitSubscriptionEstablished();

        mockMvc.perform(post(BASE_PATH + "/" + grantedWheelId + "/spin")
                        .header("Authorization", "Bearer " + ownerFixture.rawToken()))
                .andExpect(status().isCreated());

        Map<String, Object> payload = received.get(5, TimeUnit.SECONDS);
        assertThat(payload).containsEntry("wheelId", grantedWheelId);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a wheel via the REST API with the given raw {@code entries} JSON array and returns
     * its identifier — identical helper to {@code WheelSpinControllerIT}.
     *
     * @param token   the caller's raw bearer token
     * @param teamId  the owning team's id
     * @param name    the wheel name
     * @param entries a raw JSON array literal for the {@code entries} field
     * @return the string representation of the created wheel's UUID
     */
    private String createWheelWithEntries(
            final String token, final long teamId, final String name, final String entries) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"teamId\": " + teamId + ", \"name\": \"" + name + "\", \"entries\": " + entries
                                + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

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
     * Subscribes to a wheel's topic, presenting the given raw bearer token as the
     * {@code Authorization} native header (if non-{@code null}), and returns a future completed
     * with the first payload received.
     *
     * @param session the STOMP session
     * @param wheelId the wheel to subscribe to
     * @param token   the raw bearer token to present, or {@code null} to present none
     * @return a future completed with the first received payload
     */
    private CompletableFuture<Map<String, Object>> subscribeWheel(
            final StompSession session, final String wheelId, final String token) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(WheelDestinations.TOPIC_WHEEL_PREFIX + wheelId);
        if (token != null) {
            headers.add(WheelChannelInterceptor.AUTHORIZATION_HEADER, "Bearer " + token);
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
}
