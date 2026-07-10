package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.vote.PokerVote;
import fr.pivot.agilite.poker.vote.PokerVoteRepository;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PokerTicketController} exercising the full Spring context against
 * a real PostgreSQL database (Testcontainers) — covers US09.2.1 ticket creation/lookup
 * acceptance criteria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PokerTicketControllerIT {

    private static final String ROOMS_PATH = "/poker/rooms";

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

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private PokerVoteRepository voteRepository;

    @Autowired
    private PokerTicketRepository ticketRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private String otherUserToken;
    private String otherTenantToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();

        // Another user in the SAME tenant as the facilitator — used for the 403
        // facilitator-only AC. seedActiveUserWithToken() always mints a brand new tenant, so
        // the same-tenant second user is composed from the lower-level seedUser()/issueToken()
        // primitives against the facilitator's own tenantId instead.
        long sameTenantUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                facilitator.tenantId(), true);
        otherUserToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                sameTenantUserId, "active", java.time.Instant.now().plusSeconds(3600));

        // A user in a completely different tenant — used for the 404 cross-tenant AC.
        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    /**
     * Given the room's facilitator, when {@code POST .../tickets} is called with a valid title,
     * then it returns HTTP 201 with {@code id}, {@code roomId}, {@code title}, {@code status ==
     * "VOTING"}, {@code createdAt}.
     */
    @Test
    void createTicket_asFacilitator_returnsCreatedTicket() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"Estimate JIRA-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.title").value("Estimate JIRA-123"))
                .andExpect(jsonPath("$.status").value("VOTING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    /**
     * Given no ticket created yet, when {@code GET .../tickets/current} is called, then it
     * returns HTTP 200 with a {@code null} body — a legitimate state, not an error.
     */
    @Test
    void currentTicket_noneCreatedYet_returnsNullBody() throws Exception {
        String roomId = createRoom(facilitatorToken);

        MvcResult result = mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/current")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body.isBlank() || "null".equals(body.trim())).isTrue();
    }

    /**
     * Given a ticket already created, when {@code GET .../tickets/current} is called by a
     * participant (not necessarily the facilitator), then it returns that same ticket.
     */
    @Test
    void currentTicket_activeTicketExists_returnsIt() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(get(ROOMS_PATH + "/" + roomId + "/tickets/current")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.title").value("Estimate JIRA-123"));
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../tickets} is called, then it returns HTTP 403 with code
     * {@code FACILITATOR_ONLY}.
     */
    @Test
    void createTicket_notFacilitator_returns403() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherUserToken)
                        .content("{\"title\": \"Estimate JIRA-123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));
    }

    /**
     * Error case: given a room that already has a {@code VOTING} ticket, when the facilitator
     * attempts to create another one, then it returns HTTP 409 with code
     * {@code ACTIVE_TICKET_EXISTS}.
     */
    @Test
    void createTicket_activeTicketAlreadyExists_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        createTicket(roomId, facilitatorToken, "First ticket");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"Second ticket\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TICKET_EXISTS"));
    }

    /**
     * Error case: given a blank title, when {@code POST .../tickets} is called, then it returns
     * HTTP 400 with code {@code INVALID_TITLE}.
     */
    @Test
    void createTicket_blankTitle_returns400() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Error case: given a title longer than 200 characters, when {@code POST .../tickets} is
     * called, then it returns HTTP 400 with code {@code INVALID_TITLE}.
     */
    @Test
    void createTicket_tooLongTitle_returns400() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String longTitle = "a".repeat(201);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + facilitatorToken)
                        .content("{\"title\": \"" + longTitle + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../tickets} is
     * called, then it returns HTTP 404 — never confirms cross-tenant existence, never 403.
     */
    @Test
    void createTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherTenantToken)
                        .content("{\"title\": \"Title\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code POST
     * .../tickets} is called, then it returns HTTP 401.
     */
    @Test
    void createTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Title\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Security-critical AC: given a {@code VOTING} ticket with cast votes, when the facilitator
     * reveals it, then the ticket transitions in the database from {@code VOTING}/{@code
     * revealedAt == null} to {@code REVEALED}/{@code revealedAt} non-null, the response carries
     * every raw vote value and the computed consensus, and the raw JSON never carries a
     * {@code participantKey} or any other identity field.
     */
    @Test
    void revealTicket_asFacilitatorWithVotes_transitionsStatusAndReturnsConsensus() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getStatus())
                .isEqualTo(PokerTicketStatus.VOTING);
        assertThat(ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow().getRevealedAt())
                .isNull();

        seedVote(ticketId, "3");
        seedVote(ticketId, "5");
        seedVote(ticketId, "5");

        MvcResult result = mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId))
                .andExpect(jsonPath("$.status").value("REVEALED"))
                .andExpect(jsonPath("$.revealedAt").isNotEmpty())
                .andExpect(jsonPath("$.values", org.hamcrest.Matchers.containsInAnyOrder("3", "5", "5")))
                .andExpect(jsonPath("$.consensus.mean").value(4.3))
                .andExpect(jsonPath("$.consensus.median").value(5.0))
                .andExpect(jsonPath("$.consensus.majority").value("5"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("participantKey").doesNotContain("userId");

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.REVEALED);
        assertThat(persisted.getRevealedAt()).isNotNull();
    }

    /**
     * Given a ticket with zero cast votes, when it is revealed, then it still succeeds with an
     * empty {@code values} array and an all-{@code null} consensus (no completeness gate).
     */
    @Test
    void revealTicket_noVotesCast_succeedsWithNullConsensus() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.consensus.mean").doesNotExist())
                .andExpect(jsonPath("$.consensus.median").doesNotExist())
                .andExpect(jsonPath("$.consensus.majority").doesNotExist());
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when {@code POST .../reveal} is called, then it returns HTTP 403 with code {@code
     * FACILITATOR_ONLY} (the same mechanism as ticket creation, US09.2.1) and the ticket is left
     * unchanged ({@code VOTING}, {@code revealedAt == null}).
     */
    @Test
    void revealTicket_notFacilitator_returns403AndLeavesTicketUnchanged() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITATOR_ONLY"));

        PokerTicket persisted = ticketRepository.findById(UUID.fromString(ticketId)).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PokerTicketStatus.VOTING);
        assertThat(persisted.getRevealedAt()).isNull();
    }

    /**
     * Security: given a room belonging to another tenant, when {@code POST .../reveal} is
     * called, then it returns HTTP 404 — never confirms cross-tenant existence, never 403.
     */
    @Test
    void revealTicket_crossTenantRoom_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a {@code ticketId} that does not exist, when {@code POST .../reveal} is
     * called, then it returns HTTP 404.
     */
    @Test
    void revealTicket_ticketDoesNotExist_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String unknownTicketId = UUID.randomUUID().toString();

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + unknownTicketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a ticket that exists but belongs to a different room than the one in the
     * request path, when {@code POST .../reveal} is called, then it returns HTTP 404 — never
     * confirms cross-room existence.
     */
    @Test
    void revealTicket_crossRoomTicket_returns404() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");
        String otherRoomId = createRoom(facilitatorToken);

        mockMvc.perform(post(ROOMS_PATH + "/" + otherRoomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a ticket already {@code REVEALED}, when {@code POST .../reveal} is
     * called a second time, then it returns HTTP 409 with code {@code TICKET_ALREADY_REVEALED}.
     */
    @Test
    void revealTicket_alreadyRevealed_returns409() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal")
                        .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_REVEALED"));
    }

    /**
     * Error case: given the Authorization bearer header is absent, when {@code POST
     * .../reveal} is called, then it returns HTTP 401.
     */
    @Test
    void revealTicket_missingAuthorization_returns401() throws Exception {
        String roomId = createRoom(facilitatorToken);
        String ticketId = createTicket(roomId, facilitatorToken, "Estimate JIRA-123");

        mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets/" + ticketId + "/reveal"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createRoom(final String token) throws Exception {
        MvcResult result = mockMvc.perform(post(ROOMS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private String createTicket(final String roomId, final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(ROOMS_PATH + "/" + roomId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    /**
     * Seeds a vote directly via {@link PokerVoteRepository} — votes normally arrive over STOMP
     * (US09.2.1), but this IT only needs them to already exist in the database ahead of a reveal.
     *
     * @param ticketId the ticket to vote on
     * @param value    the raw card value
     */
    private void seedVote(final String ticketId, final String value) {
        voteRepository.save(new PokerVote(
                UUID.fromString(ticketId), "participant-key-" + UUID.randomUUID(), value, Instant.now()));
    }
}
