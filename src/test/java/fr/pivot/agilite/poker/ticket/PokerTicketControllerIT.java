package fr.pivot.agilite.poker.ticket;

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
}
