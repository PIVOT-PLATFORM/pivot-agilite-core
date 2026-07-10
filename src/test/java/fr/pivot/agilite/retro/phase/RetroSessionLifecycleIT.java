package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.retro.ws.RetroChannelInterceptor;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving the full retrospective session lifecycle end-to-end (US20.1.2c AC:
 * "Test TI: séquence complète CONTRIBUTION → REVUE → VOTE → ACTION → SESSION_CLOSED rejouée de
 * bout en bout ... chaque transition de phase vérifiée par son événement STOMP respectif") —
 * mirrors {@code RetroVoteCastingIT}'s raw-payload-inspection structure and helpers, extended one
 * phase further to also cover the {@code ACTION} → {@code CLOSED} transition and the resulting
 * write-rejection behaviour (US20.1.2c).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroSessionLifecycleIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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

    private String facilitatorToken;
    private long teamId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
        long tenantId = facilitator.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitator.userId());
    }

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
     * Drives a session through every phase — CONTRIBUTION → REVUE → VOTE → ACTION → CLOSED —
     * asserting each transition is signalled by its own respective STOMP event
     * ({@code PHASE_CHANGED} ×3, then {@code SESSION_CLOSED}), then proves the closed session is
     * genuinely read-only: a further card submission and a further vote are both rejected via
     * {@code /user/queue/errors} (never an unhandled exception, never a broadcast to the room
     * topic), and the REST detail endpoint reflects {@code CLOSED} as the final state.
     */
    @Test
    void fullLifecycle_contributionToClosed_eachTransitionVerifiedByItsStompEvent() throws Exception {
        String sessionId = createSession();
        join(sessionId, facilitatorToken);
        JsonNode participantGrant = join(sessionId, null);
        String participantAccessToken = participantGrant.get("accessToken").asText();

        StompSession participantWs = connect();
        BlockingQueue<String> roomTopicRaw = subscribeRawQueue(
                participantWs, "/topic/agilite/retro/" + sessionId, participantAccessToken);
        BlockingQueue<String> participantErrors = subscribeRawQueue(participantWs, "/user/queue/errors", null);
        awaitSubscriptionEstablished();

        // CONTRIBUTION — submit a card.
        sendCard(participantWs, sessionId, participantAccessToken, "Great sprint pace", "went-well", false);
        assertThat(poll(roomTopicRaw)).contains("CARD_ADDED");

        // CONTRIBUTION -> REVUE.
        closeContribution(sessionId, facilitatorToken);
        String phaseChangedToRevue = poll(roomTopicRaw);
        assertThat(phaseChangedToRevue).contains("PHASE_CHANGED").contains("\"REVUE\"");

        String revealPayload = reveal(sessionId, facilitatorToken);
        assertThat(poll(roomTopicRaw)).contains("CARDS_REVEALED");
        String cardId = objectMapper.readTree(revealPayload)
                .get("columns").get("went-well").get(0).get("id").asText();

        // REVUE -> VOTE.
        openVote(sessionId, facilitatorToken);
        String phaseChangedToVote = poll(roomTopicRaw);
        assertThat(phaseChangedToVote).contains("PHASE_CHANGED").contains("\"VOTE\"");

        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        assertThat(poll(roomTopicRaw)).contains("VOTE_CAST");

        // VOTE -> ACTION, ranking carried alongside PHASE_CHANGED (US20.1.2b).
        closeVote(sessionId, facilitatorToken);
        String phaseChangedToAction = poll(roomTopicRaw);
        assertThat(phaseChangedToAction).contains("PHASE_CHANGED").contains("\"ACTION\"").contains("rankedCards");
        JsonNode ranking = objectMapper.readTree(phaseChangedToAction).get("rankedCards");
        assertThat(ranking.get(0).get("cardId").asText()).isEqualTo(cardId);
        assertThat(ranking.get(0).get("voteCount").asLong()).isEqualTo(1L);

        // ACTION -> CLOSED — its own dedicated event, not PHASE_CHANGED (US20.1.2c).
        closeSession(sessionId, facilitatorToken);
        String sessionClosed = poll(roomTopicRaw);
        assertThat(sessionClosed).contains("SESSION_CLOSED").contains("\"ACTION\"");
        assertThat(sessionClosed).doesNotContain("PHASE_CHANGED");

        // REST detail endpoint reflects the terminal state.
        mockMvc.perform(
                        get("/retro/sessions/" + sessionId)
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CLOSED"));

        // A closed session rejects further writes — vote.
        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        String voteRejected = poll(participantErrors);
        assertThat(voteRejected.toLowerCase()).contains("retro session is closed");
        assertThat(roomTopicRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // A closed session rejects further writes — card.
        sendCard(participantWs, sessionId, participantAccessToken, "Too late to contribute", "went-well", false);
        String cardRejected = poll(participantErrors);
        assertThat(cardRejected.toLowerCase()).contains("retro session is closed");
        assertThat(roomTopicRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // A closed session rejects a further manual close attempt with a conflict, not a crash.
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/retro/sessions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Sprint Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode join(final String sessionId, final String bearerToken) throws Exception {
        var requestBuilder = post("/retro/sessions/" + sessionId + "/participants");
        if (bearerToken != null) {
            requestBuilder = requestBuilder.header("Authorization", "Bearer " + bearerToken);
        }
        MvcResult result = mockMvc.perform(requestBuilder)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void closeContribution(final String sessionId, final String bearerToken) throws Exception {
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk());
    }

    private String reveal(final String sessionId, final String bearerToken) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private void openVote(final String sessionId, final String bearerToken) throws Exception {
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/vote/open")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk());
    }

    private void closeVote(final String sessionId, final String bearerToken) throws Exception {
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/vote/close")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk());
    }

    private void closeSession(final String sessionId, final String bearerToken) throws Exception {
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CLOSED"));
    }

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

    private BlockingQueue<String> subscribeRawQueue(
            final StompSession session, final String destination, final String accessToken) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        if (accessToken != null) {
            headers.add(RetroChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        }
        session.subscribe(headers, new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders stompHeaders) {
                return byte[].class;
            }

            @Override
            public void handleFrame(final StompHeaders stompHeaders, final Object payload) {
                queue.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return queue;
    }

    private void sendCard(
            final StompSession session, final String sessionId, final String accessToken,
            final String content, final String columnKey, final boolean anonymous) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/agilite/retro/" + sessionId + "/cards");
        headers.add(RetroChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        session.send(headers, Map.of("content", content, "columnKey", columnKey, "anonymous", anonymous));
    }

    private void sendVote(
            final StompSession session, final String sessionId, final String accessToken, final String cardId) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/agilite/retro/" + sessionId + "/votes");
        headers.add(RetroChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        session.send(headers, Map.of("cardId", cardId));
    }

    private static String poll(final BlockingQueue<String> queue) throws InterruptedException {
        String value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("expected a message within 5s").isNotNull();
        return value;
    }

    /**
     * Brief pause so the broker has registered the just-issued SUBSCRIBEs before any broadcast is
     * sent — same precedent as {@code RetroVoteCastingIT}/{@code RetroCardSubmissionIT}.
     */
    private void awaitSubscriptionEstablished() throws InterruptedException {
        Thread.sleep(200);
    }
}
