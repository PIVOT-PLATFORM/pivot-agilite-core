package fr.pivot.agilite.retro.vote.ws;

import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving the US20.1.2b dot-voting flow end-to-end, against a real STOMP
 * transport, real Redis-backed {@code RetroAccessGrantService}, and real PostgreSQL
 * (Testcontainers) — mirrors {@code RetroCardSubmissionIT}'s raw-payload-inspection structure and
 * helpers.
 *
 * <p><strong>Raw payload inspection.</strong> Every subscription requests {@code byte[].class} as
 * its {@link StompFrameHandler} payload type, bypassing typed JSON conversion, so assertions read
 * the literal JSON text a (possibly buggy) DTO mapping could otherwise have hidden a leak behind —
 * in particular, that {@code VOTE_CAST}/{@code VOTE_UNCAST} never carry the voter's access token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroVoteCastingIT {

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

    @Autowired
    private RetroCardRepository cardRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<StompSession> openSessions = new ArrayList<>();

    private String facilitatorToken;
    private long tenantId;
    private long teamId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
        tenantId = facilitator.tenantId();
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
     * End to end: facilitator + anonymous participant join, the session is driven
     * CONTRIBUTION → REVUE (close + reveal) → VOTE (open), the participant casts a vote on the
     * revealed card (raw {@code VOTE_CAST} payload never contains any access-token substring),
     * votes again on the same card (multiple votes on one card allowed, count becomes 2), then a
     * third vote exhausts the default-3 balance; a fourth attempt is rejected via
     * {@code /user/queue/errors} with no further {@code VOTE_CAST} broadcast; a vote on a card
     * belonging to a different session is rejected the same way; the facilitator's
     * {@code closeVote} broadcasts {@code PHASE_CHANGED} whose raw payload contains
     * {@code "rankedCards"} with the voted card ranked first.
     */
    @Test
    void voteCastExhaustionAndCloseVote_endToEnd() throws Exception {
        String sessionId = createSession();
        join(sessionId, facilitatorToken);
        JsonNode participantGrant = join(sessionId, null);
        String participantAccessToken = participantGrant.get("accessToken").asText();

        StompSession participantWs = connect();
        BlockingQueue<String> roomTopicRaw = subscribeRawQueue(
                participantWs, "/topic/agilite/retro/" + sessionId, participantAccessToken);
        BlockingQueue<String> participantErrors = subscribeRawQueue(participantWs, "/user/queue/errors", null);
        BlockingQueue<String> participantVotes = subscribeRawQueue(participantWs, "/user/queue/votes", null);
        awaitSubscriptionEstablished();

        sendCard(participantWs, sessionId, participantAccessToken, "Great sprint pace", "went-well", false);
        String cardAdded = poll(roomTopicRaw);
        assertThat(cardAdded).contains("CARD_ADDED");

        closeContribution(sessionId, facilitatorToken);
        assertThat(poll(roomTopicRaw)).contains("PHASE_CHANGED");

        String revealPayload = reveal(sessionId, facilitatorToken);
        assertThat(poll(roomTopicRaw)).contains("CARDS_REVEALED");
        String cardId = objectMapper.readTree(revealPayload)
                .get("columns").get("went-well").get(0).get("id").asText();

        openVote(sessionId, facilitatorToken);
        String phaseChangedToVote = poll(roomTopicRaw);
        assertThat(phaseChangedToVote).contains("PHASE_CHANGED").contains("\"VOTE\"");

        // First cast.
        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        String voteCast1 = poll(roomTopicRaw);
        assertThat(voteCast1).contains("VOTE_CAST");
        assertThat(voteCast1).doesNotContain(participantAccessToken);
        assertThat(voteCast1).doesNotContain("voterToken");
        assertThat(objectMapper.readTree(voteCast1).get("voteCount").asLong()).isEqualTo(1L);
        String balance1 = poll(participantVotes);
        assertThat(balance1).contains("VOTE_BALANCE");
        assertThat(objectMapper.readTree(balance1).get("votesRemaining").asInt()).isEqualTo(2);
        assertThat(objectMapper.readTree(balance1).get("votesAllowed").asInt()).isEqualTo(3);

        // Second cast on the same card — multiple votes on one card are allowed.
        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        String voteCast2 = poll(roomTopicRaw);
        assertThat(objectMapper.readTree(voteCast2).get("voteCount").asLong()).isEqualTo(2L);
        poll(participantVotes);

        // Third cast — exhausts the default-3 balance.
        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        String voteCast3 = poll(roomTopicRaw);
        assertThat(objectMapper.readTree(voteCast3).get("voteCount").asLong()).isEqualTo(3L);
        String balance3 = poll(participantVotes);
        assertThat(objectMapper.readTree(balance3).get("votesRemaining").asInt()).isZero();

        // Fourth attempt — rejected, no further VOTE_CAST broadcast.
        sendVote(participantWs, sessionId, participantAccessToken, cardId);
        String exhaustedError = poll(participantErrors);
        assertThat(exhaustedError.toLowerCase()).contains("no remaining votes");
        assertThat(roomTopicRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // Voting on a card belonging to a different session — rejected (404-equivalent).
        String otherSessionCardId = seedCardInOtherSession();
        sendVote(participantWs, sessionId, participantAccessToken, otherSessionCardId);
        String crossSessionError = poll(participantErrors);
        assertThat(crossSessionError.toLowerCase()).contains("not found");
        assertThat(roomTopicRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();

        // Facilitator closes the vote phase — PHASE_CHANGED carries the ranking, voted card first.
        closeVote(sessionId, facilitatorToken);
        String phaseChangedToAction = poll(roomTopicRaw);
        assertThat(phaseChangedToAction).contains("PHASE_CHANGED").contains("rankedCards");
        JsonNode ranking = objectMapper.readTree(phaseChangedToAction).get("rankedCards");
        assertThat(ranking.get(0).get("cardId").asText()).isEqualTo(cardId);
        assertThat(ranking.get(0).get("voteCount").asLong()).isEqualTo(3L);
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

    /** Seeds a card directly into a brand-new, separate session — used for the cross-session rejection case. */
    private String seedCardInOtherSession() throws Exception {
        String otherSessionId = createSession();
        RetroCard card = cardRepository.save(new RetroCard(
                UUID.fromString(otherSessionId), "went-well", "Other session's card", true, null, Instant.now()));
        return card.getId().toString();
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
     * sent — same precedent as {@code RetroCardSubmissionIT}/{@code PokerRoomIsolationIT}.
     */
    private void awaitSubscriptionEstablished() throws InterruptedException {
        Thread.sleep(200);
    }
}
