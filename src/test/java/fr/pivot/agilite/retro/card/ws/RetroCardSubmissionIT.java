package fr.pivot.agilite.retro.card.ws;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving the central US20.1.2a security AC end-to-end, against a real STOMP
 * transport, real Redis-backed {@code RetroAccessGrantService}, and real PostgreSQL
 * (Testcontainers):
 *
 * <blockquote>Test TI: une card soumise n'est visible en clair pour aucun participant autre que
 * l'animateur avant l'événement {@code CARDS_REVEALED} (vérifié via un client STOMP de test qui
 * inspecte le payload brut).</blockquote>
 *
 * <p><strong>Raw payload inspection, not just typed deserialization.</strong> Every subscription
 * in this test requests {@code byte[].class} as its {@link StompFrameHandler} payload type —
 * Spring's message conversion short-circuits to the untouched wire bytes whenever the target type
 * already matches the payload's runtime type (a {@code byte[]} STOMP frame body), bypassing the
 * configured {@link JacksonJsonMessageConverter} entirely. This is what lets the test assert on
 * the literal JSON text received, not on what a (possibly buggy) DTO mapping would have exposed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroCardSubmissionIT {

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
     * The central US20.1.2a Test TI, end to end:
     * <ol>
     *   <li>A non-facilitator participant submits a card with secret content.</li>
     *   <li>The masked event on the regular session topic never contains the content, at the
     *       raw frame level (not merely "the DTO field is null").</li>
     *   <li>The facilitator-only topic DOES carry the full content.</li>
     *   <li>The same non-facilitator participant is denied when attempting to subscribe to the
     *       facilitator-only topic directly.</li>
     *   <li>Once the facilitator closes contribution and triggers reveal, the previously-masked
     *       participant now receives the content in clear via {@code CARDS_REVEALED}.</li>
     * </ol>
     */
    @Test
    void cardContentNeverVisibleInClearToNonFacilitatorBeforeReveal() throws Exception {
        String sessionId = createSession();
        String facilitatorAccessToken = join(sessionId, facilitatorToken).get("accessToken").asText();
        JsonNode participantGrant = join(sessionId, null);
        String participantAccessToken = participantGrant.get("accessToken").asText();
        assertThat(participantGrant.get("facilitator").asBoolean()).isFalse();

        StompSession facilitatorWs = connect();
        StompSession participantWs = connect();

        BlockingQueue<String> maskedTopicRaw = subscribeRawQueue(
                participantWs, "/topic/agilite/retro/" + sessionId, participantAccessToken);
        BlockingQueue<String> facilitatorTopicRaw = subscribeRawQueue(
                facilitatorWs, "/topic/agilite/retro/" + sessionId + "/facilitator", facilitatorAccessToken);
        BlockingQueue<String> participantErrors = subscribeRawQueue(participantWs, "/user/queue/errors", null);
        awaitSubscriptionEstablished();

        // Non-facilitator attempts to subscribe directly to the facilitator-only topic: denied.
        subscribeRawQueue(participantWs, "/topic/agilite/retro/" + sessionId + "/facilitator", participantAccessToken);
        awaitSubscriptionEstablished();

        String secretContent = "Super secret retro feedback " + UUID.randomUUID();
        sendCard(participantWs, sessionId, participantAccessToken, secretContent, "went-well", false);

        // Masked payload: never the content, at the raw frame level.
        String maskedPayload = poll(maskedTopicRaw);
        assertThat(maskedPayload).contains("\"CARD_ADDED\"");
        assertThat(maskedPayload).doesNotContain(secretContent);
        assertThat(maskedPayload).doesNotContain("\"content\"");
        assertThat(maskedPayload).doesNotContain("\"cardId\"");
        JsonNode maskedNode = objectMapper.readTree(maskedPayload);
        assertThat(maskedNode.get("cardCount").asLong()).isEqualTo(1L);
        assertThat(maskedNode.get("columnKey").asText()).isEqualTo("went-well");

        // Facilitator-only payload: full content.
        String facilitatorPayload = poll(facilitatorTopicRaw);
        assertThat(facilitatorPayload).contains(secretContent);

        // Denied SUBSCRIBE to the facilitator topic notified the participant's own error queue.
        String errorPayload = poll(participantErrors);
        assertThat(errorPayload.toLowerCase()).contains("facilitator");

        // Facilitator closes contribution then reveals — the previously-masked participant now
        // sees the content in clear via CARDS_REVEALED on the very same topic subscription.
        // closeContribution itself also broadcasts a PHASE_CHANGED event on this same topic
        // first — consumed here before the CARDS_REVEALED that reveal() triggers next.
        closeContribution(sessionId, facilitatorToken);
        String phaseChangedPayload = poll(maskedTopicRaw);
        assertThat(phaseChangedPayload).contains("PHASE_CHANGED");

        reveal(sessionId, facilitatorToken);
        String revealPayload = poll(maskedTopicRaw);
        assertThat(revealPayload).contains("CARDS_REVEALED");
        assertThat(revealPayload).contains(secretContent);
    }

    /**
     * Given a session still in CONTRIBUTION, when a card is submitted with invalid (blank)
     * content, then it is rejected via an error notification to the sender alone — nothing is
     * broadcast to the masked topic.
     */
    @Test
    void blankCardContentIsRejectedWithoutBroadcast() throws Exception {
        String sessionId = createSession();
        JsonNode participantGrant = join(sessionId, null);
        String accessToken = participantGrant.get("accessToken").asText();

        StompSession participantWs = connect();
        BlockingQueue<String> maskedTopicRaw = subscribeRawQueue(
                participantWs, "/topic/agilite/retro/" + sessionId, accessToken);
        BlockingQueue<String> errors = subscribeRawQueue(participantWs, "/user/queue/errors", null);
        awaitSubscriptionEstablished();

        sendCard(participantWs, sessionId, accessToken, "   ", "went-well", false);

        String error = poll(errors);
        assertThat(error).isNotBlank();
        assertThat(maskedTopicRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();
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

    private void reveal(final String sessionId, final String bearerToken) throws Exception {
        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk());
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

    /**
     * Subscribes to {@code destination}, feeding every received frame's raw bytes (decoded as
     * UTF-8 text) into a queue — see the class JavaDoc for why {@code byte[].class} bypasses
     * JSON conversion entirely.
     */
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

    private static String poll(final BlockingQueue<String> queue) throws InterruptedException {
        String value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("expected a message within 5s").isNotNull();
        return value;
    }

    /**
     * Brief pause so the broker has registered the just-issued SUBSCRIBE before any broadcast is
     * sent — otherwise an early broadcast could race the subscription's registration (same
     * precedent as {@code PokerRoomIsolationIT}).
     */
    private void awaitSubscriptionEstablished() throws InterruptedException {
        Thread.sleep(200);
    }
}
