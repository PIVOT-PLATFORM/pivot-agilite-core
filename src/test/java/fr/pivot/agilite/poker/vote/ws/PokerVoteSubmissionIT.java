package fr.pivot.agilite.poker.vote.ws;

import fr.pivot.agilite.poker.ws.PokerChannelInterceptor;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving the central US09.2.1 security AC end-to-end, against a real STOMP
 * transport, real Redis-backed {@code RoomAccessGrantService}/{@code
 * PokerParticipantRegistryService}, and real PostgreSQL (Testcontainers):
 *
 * <blockquote>Aucun abonné — participant ou facilitateur — ne reçoit jamais une valeur de vote ni
 * une identité de votant avant révélation, y compris en inspectant les octets bruts de la
 * trame.</blockquote>
 *
 * <p><strong>Raw payload inspection, not just typed deserialization</strong> — same technique as
 * {@code RetroCardSubmissionIT} (US20.1.2a): every subscription requests {@code byte[].class} as
 * its {@link StompFrameHandler} payload type, bypassing Jackson conversion entirely, so assertions
 * run against the literal JSON text actually placed on the wire.
 *
 * <p><strong>Unlike {@code RetroCardSubmissionIT}, there is no facilitator-only preview
 * channel here</strong> — planning poker masks votes for absolutely everyone, including the
 * facilitator, until US09.2.2's reveal (ADR-026 §2 / Gate 1 clarification, see the US09.2.1
 * backlog file). Both sessions below subscribe to the very same room topic and are held to the
 * very same masking assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PokerVoteSubmissionIT {

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

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
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
     * The central US09.2.1 Test TI, end to end:
     * <ol>
     *   <li>A room is created (facilitator gets its own access token, US09.2.1's Gate 1 fix) and
     *       a participant joins it.</li>
     *   <li>A ticket is created; both the facilitator and the participant subscribe to the room
     *       topic with their own access tokens.</li>
     *   <li>The participant votes — the masked {@code VOTE_CAST} received by <em>both</em>
     *       sessions never carries the chosen value, at the raw frame level, only the aggregate
     *       counters ({@code totalParticipants == 2}: the facilitator's own creation-time grant
     *       plus the participant's join-time grant — the roster counts distinct access-token
     *       grants, not distinct authenticated users, see {@code PokerParticipantRegistryService}).</li>
     *   <li>The participant changes their vote — {@code votedCount} stays at 1 (no double count),
     *       still never leaking any value.</li>
     * </ol>
     */
    @Test
    void voteValueNeverVisibleToAnyoneIncludingFacilitatorBeforeReveal() throws Exception {
        JsonNode room = createRoom(facilitatorToken);
        String roomId = room.get("id").asText();
        String facilitatorAccessToken = room.get("accessToken").asText();
        String participantAccessToken = joinRoom(roomId, facilitatorToken).get("accessToken").asText();
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123").get("id").asText();

        StompSession facilitatorWs = connect();
        StompSession participantWs = connect();

        BlockingQueue<String> facilitatorRaw = subscribeRawQueue(facilitatorWs, roomId, facilitatorAccessToken);
        BlockingQueue<String> participantRaw = subscribeRawQueue(participantWs, roomId, participantAccessToken);
        awaitSubscriptionEstablished();

        sendVote(participantWs, roomId, participantAccessToken, ticketId, "13");

        String facilitatorPayload = poll(facilitatorRaw);
        assertMaskedVoteCast(facilitatorPayload, ticketId, 1, 2);
        String participantPayload = poll(participantRaw);
        assertMaskedVoteCast(participantPayload, ticketId, 1, 2);

        // Change of vote before reveal: votedCount stays at 1 (same participant), never leaking
        // either the old or the new value. assertMaskedVoteCast already proves this structurally
        // (asserts the absence of the "value" JSON key on the raw payload) — a further raw
        // substring check on the literal card values ("13"/"89") was flaky: roomId/ticketId are
        // random hex UUIDs, which can coincidentally contain either digit sequence, failing this
        // assertion with no relation to any actual value leak.
        sendVote(participantWs, roomId, participantAccessToken, ticketId, "89");
        String changedPayload = poll(facilitatorRaw);
        assertMaskedVoteCast(changedPayload, ticketId, 1, 2);
    }

    /**
     * Given two distinct participants (facilitator + one joiner) in the same room, when each
     * votes independently, then {@code votedCount} correctly reflects 2 distinct participants —
     * an upsert is keyed by participant, never by session/message.
     */
    @Test
    void twoDistinctParticipantsBothCountedIndependently() throws Exception {
        JsonNode room = createRoom(facilitatorToken);
        String roomId = room.get("id").asText();
        String facilitatorAccessToken = room.get("accessToken").asText();
        String participantAccessToken = joinRoom(roomId, facilitatorToken).get("accessToken").asText();
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123").get("id").asText();

        StompSession facilitatorWs = connect();
        BlockingQueue<String> facilitatorRaw = subscribeRawQueue(facilitatorWs, roomId, facilitatorAccessToken);
        awaitSubscriptionEstablished();

        sendVote(facilitatorWs, roomId, facilitatorAccessToken, ticketId, "5");
        String firstPayload = poll(facilitatorRaw);
        assertMaskedVoteCast(firstPayload, ticketId, 1, 2);

        StompSession participantWs = connect();
        sendVote(participantWs, roomId, participantAccessToken, ticketId, "8");
        String secondPayload = poll(facilitatorRaw);
        assertMaskedVoteCast(secondPayload, ticketId, 2, 2);
    }

    /**
     * Security AC (cross-room isolation): given a valid ticket belonging to room A, when a
     * participant of a completely different room B submits a vote referencing that ticket on
     * room B's own destination, then it is rejected — no {@code VOTE_CAST} is ever broadcast on
     * room B, and the ticket's existence in room A is never confirmed to room B's participant.
     */
    @Test
    void ticketFromAnotherRoomIsRejectedOnCrossRoomVote() throws Exception {
        JsonNode roomA = createRoom(facilitatorToken);
        String roomAId = roomA.get("id").asText();
        String ticketIdInRoomA = createTicket(roomAId, facilitatorToken, "Room A ticket").get("id").asText();

        AuthFixture otherFacilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JsonNode roomB = createRoom(otherFacilitator.rawToken());
        String roomBId = roomB.get("id").asText();
        String roomBAccessToken = roomB.get("accessToken").asText();

        StompSession roomBWs = connect();
        BlockingQueue<String> roomBRaw = subscribeRawQueue(roomBWs, roomBId, roomBAccessToken);
        BlockingQueue<String> roomBErrors = subscribeRawQueue(roomBWs, "/user/queue/errors", null);
        awaitSubscriptionEstablished();

        sendVote(roomBWs, roomBId, roomBAccessToken, ticketIdInRoomA, "5");

        String error = poll(roomBErrors);
        assertThat(error.toLowerCase()).contains("not found");
        assertThat(roomBRaw.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    /**
     * Error case: given a card value that is not part of the fixed Fibonacci deck, when it is
     * submitted, then it is rejected (error to sender alone) without any broadcast.
     */
    @Test
    void invalidCardValueIsRejectedWithoutBroadcast() throws Exception {
        JsonNode room = createRoom(facilitatorToken);
        String roomId = room.get("id").asText();
        String facilitatorAccessToken = room.get("accessToken").asText();
        String ticketId = createTicket(roomId, facilitatorToken, "Title").get("id").asText();

        StompSession ws = connect();
        BlockingQueue<String> raw = subscribeRawQueue(ws, roomId, facilitatorAccessToken);
        BlockingQueue<String> errors = subscribeRawQueue(ws, "/user/queue/errors", null);
        awaitSubscriptionEstablished();

        sendVote(ws, roomId, facilitatorAccessToken, ticketId, "42");

        String error = poll(errors);
        assertThat(error).isNotBlank();
        assertThat(raw.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertMaskedVoteCast(
            final String payload, final String ticketId, final long votedCount, final long totalParticipants)
            throws Exception {
        assertThat(payload).contains("\"VOTE_CAST\"");
        assertThat(payload).doesNotContain("\"value\"");
        JsonNode node = objectMapper.readTree(payload);
        assertThat(node.get("ticketId").asText()).isEqualTo(ticketId);
        assertThat(node.get("votedCount").asLong()).isEqualTo(votedCount);
        assertThat(node.get("totalParticipants").asLong()).isEqualTo(totalParticipants);
    }

    private JsonNode createRoom(final String bearerToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/poker/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearerToken)
                        .content("{\"name\": \"Sprint 8 estimation\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode joinRoom(final String roomId, final String bearerToken) throws Exception {
        String inviteCode = fetchInviteCode(roomId, bearerToken);
        MvcResult result = mockMvc.perform(post("/poker/rooms/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearerToken)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String fetchInviteCode(final String roomId, final String bearerToken) throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/poker/rooms/" + roomId)
                                .header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("inviteCode").asText();
    }

    private JsonNode createTicket(final String roomId, final String bearerToken, final String title)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/poker/rooms/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearerToken)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
            final StompSession session, final String roomIdOrDestination, final String accessToken) {
        String destination = roomIdOrDestination.startsWith("/")
                ? roomIdOrDestination
                : "/topic/agilite/poker/" + roomIdOrDestination;
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        if (accessToken != null) {
            headers.add(PokerChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
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

    private void sendVote(
            final StompSession session, final String roomId, final String accessToken,
            final String ticketId, final String value) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/agilite/poker/" + roomId + "/vote");
        headers.add(PokerChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        session.send(headers, Map.of("ticketId", ticketId, "value", value));
    }

    private static String poll(final BlockingQueue<String> queue) throws InterruptedException {
        String value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("expected a message within 5s").isNotNull();
        return value;
    }

    /**
     * Brief pause so the broker has registered the just-issued SUBSCRIBE before any broadcast is
     * sent — otherwise an early broadcast could race the subscription's registration (same
     * precedent as {@code PokerRoomIsolationIT}/{@code RetroCardSubmissionIT}).
     */
    private void awaitSubscriptionEstablished() throws InterruptedException {
        Thread.sleep(200);
    }
}
