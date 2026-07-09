package fr.pivot.agilite.poker;

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
 * Integration tests for {@link PokerRoomController} exercising the full Spring context against a
 * real PostgreSQL database (Testcontainers) — covers US09.1.1 acceptance criteria.
 *
 * <p>Each test authenticates via real bearer tokens issued for tenants/users seeded through
 * {@link PlatformAuthTestSupport} (same pattern as {@code pivot-collaboratif-core}'s EN08.3) —
 * tenant isolation is exercised with distinct seeded identities.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix. Paths used in tests therefore start
 * with {@code /poker/rooms} (not {@code /api/agilite/...}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PokerRoomControllerIT {

    private static final String BASE_PATH = "/poker/rooms";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived datasource and Redis connection properties to the Spring
     * context via dynamic property sources, and seeds the {@code public} schema (owned by
     * {@code pivot-core}) before the Spring context and its Flyway run start.
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

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long userA;
    private String tokenA;
    private long tenantB;
    private String tokenB;

    /**
     * Sets up MockMvc from the web application context and seeds two distinct tenant/user/token
     * fixtures (A and B) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        userA = fixtureA.userId();
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms
    // -------------------------------------------------------------------------

    /**
     * Given a facilitator authenticated with a valid room name, when POST /poker/rooms is
     * called, then it returns HTTP 201 with id, name, a 6-character inviteCode, sequence
     * FIBONACCI, cardValues, facilitatorUserId, active true, createdAt/expiresAt, and wsTopic.
     */
    @Test
    void createRoom_returnsCreatedWithAllFields() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Sprint 8 estimation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sprint 8 estimation"))
                .andExpect(jsonPath("$.inviteCode").isString())
                .andExpect(jsonPath("$.sequence").value("FIBONACCI"))
                .andExpect(jsonPath("$.cardValues").isArray())
                .andExpect(jsonPath("$.cardValues.length()").value(12))
                .andExpect(jsonPath("$.facilitatorUserId").value(userA))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.wsTopic").isString());
    }

    /**
     * Given a created room, then its inviteCode is exactly 6 characters from the reduced
     * alphabet (no ambiguous 0/1/I/O).
     */
    @Test
    void createRoom_inviteCodeIsSixCharsFromReducedAlphabet() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String inviteCode = body.get("inviteCode").asText();

        assertThat(inviteCode).hasSize(6);
        assertThat(inviteCode).matches("[" + InviteCodeGenerator.ALPHABET + "]{6}");
    }

    /**
     * Given no expirationHours in the request, when a room is created, then wsTopic follows the
     * ADR-026 §2 convention {@code /topic/agilite/poker/{id}}.
     */
    @Test
    void createRoom_wsTopicFollowsAdr026Convention() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        long id = body.get("id").asLong();

        assertThat(body.get("wsTopic").asText()).isEqualTo("/topic/agilite/poker/" + id);
    }

    /**
     * Given the facilitator who created a room, then facilitatorUserId in the response equals
     * the caller's own user id resolved server-side — never a client-supplied value (there is no
     * such field to supply in the request body).
     */
    @Test
    void createRoom_facilitatorIsServerResolvedCaller() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facilitatorUserId").value(userA));
    }

    /**
     * Error case: given an empty name, when POST /poker/rooms is called, then it returns HTTP
     * 400 with code INVALID_NAME.
     */
    @Test
    void createRoom_withEmptyName_returns400WithInvalidNameCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    /**
     * Error case: given a name longer than 120 characters, when POST /poker/rooms is called,
     * then it returns HTTP 400 with code INVALID_NAME.
     */
    @Test
    void createRoom_withTooLongName_returns400WithInvalidNameCode() throws Exception {
        String longName = "a".repeat(121);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"" + longName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    /**
     * Error case: given expirationHours = 0, when POST /poker/rooms is called, then it returns
     * HTTP 400 with code INVALID_EXPIRATION.
     */
    @Test
    void createRoom_withZeroExpirationHours_returns400WithInvalidExpirationCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\", \"expirationHours\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPIRATION"));
    }

    /**
     * Error case: given expirationHours = 169 (> 168, the 7-day cap), when POST /poker/rooms is
     * called, then it returns HTTP 400 with code INVALID_EXPIRATION.
     */
    @Test
    void createRoom_withTooLargeExpirationHours_returns400WithInvalidExpirationCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\", \"expirationHours\": 169}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXPIRATION"));
    }

    /**
     * Given expirationHours = 1 (within bounds), when POST /poker/rooms is called, then it
     * returns HTTP 201 and expiresAt is roughly 1 hour after createdAt.
     */
    @Test
    void createRoom_withValidExpirationHours_appliesIt() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Room\", \"expirationHours\": 1}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        java.time.Instant createdAt = java.time.Instant.parse(body.get("createdAt").asText());
        java.time.Instant expiresAt = java.time.Instant.parse(body.get("expiresAt").asText());

        assertThat(java.time.Duration.between(createdAt, expiresAt).toMinutes()).isEqualTo(60L);
    }

    /**
     * Error case: given the Authorization bearer header is absent, when POST /poker/rooms is
     * called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void createRoom_missingAuthorization_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given a garbage bearer token, when POST /poker/rooms is called, then it
     * returns HTTP 401 Unauthorized (unknown token collapses to the same generic response as
     * every other rejection reason).
     */
    @Test
    void createRoom_invalidToken_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer not-a-real-token")
                        .content("{\"name\": \"Room\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /poker/rooms/{roomId}
    // -------------------------------------------------------------------------

    /**
     * Given a room created by the caller's own tenant, when GET /poker/rooms/{roomId} is called,
     * then it returns HTTP 200 with the same fields as creation.
     */
    @Test
    void findById_ownTenant_returnsRoom() throws Exception {
        long roomId = createRoomFor(tokenA, "My Room");

        mockMvc.perform(get(BASE_PATH + "/" + roomId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(roomId))
                .andExpect(jsonPath("$.name").value("My Room"));
    }

    /**
     * Security: given a room belonging to tenant A, when a user from tenant B requests it, then
     * it returns HTTP 404 (cross-tenant isolation — never 403, never confirming existence).
     */
    @Test
    void findById_crossTenant_returns404() throws Exception {
        long roomId = createRoomFor(tokenA, "Tenant A Room");

        mockMvc.perform(get(BASE_PATH + "/" + roomId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a room id that does not exist at all, when GET /poker/rooms/{roomId} is
     * called, then it returns HTTP 404.
     */
    @Test
    void findById_nonExistentRoom_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/999999999")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when GET
     * /poker/rooms/{roomId} is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void findById_missingAuthorization_returns401() throws Exception {
        long roomId = createRoomFor(tokenA, "Room");

        mockMvc.perform(get(BASE_PATH + "/" + roomId))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a room via the API using the given caller's bearer token and returns its id.
     *
     * @param token the caller's raw bearer token
     * @param name  the room name
     * @return the created room's numeric id
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private long createRoomFor(final String token, final String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }
}
