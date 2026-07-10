package fr.pivot.agilite.poker;

import fr.pivot.agilite.exception.GlobalExceptionHandler;
import fr.pivot.agilite.poker.exception.PokerFacilitatorOnlyException;
import fr.pivot.agilite.poker.ws.RoomAccessGrantService;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
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

    @Autowired
    private RoomAccessGrantService roomAccessGrantService;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

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
     * US09.2.1: given a room is created, when the response is built, then the facilitator
     * receives their own non-blank {@code accessToken}, and it is immediately usable — {@code
     * RoomAccessGrantService#hasAccess} is true for this exact (roomId, accessToken) pair right
     * after the call, proving a real grant was issued (not just echoed), exactly mirroring what
     * US09.1.2's join flow already does for a joining participant.
     */
    @Test
    void createRoom_mintsFacilitatorAccessTokenThatIsImmediatelyUsable() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Sprint 8 estimation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String roomId = body.get("id").asText();
        String accessToken = body.get("accessToken").asText();

        assertThat(roomAccessGrantService.hasAccess(java.util.UUID.fromString(roomId), accessToken)).isTrue();
    }

    /**
     * US09.2.1: given a room created by the caller, when it is later fetched via {@code GET
     * /poker/rooms/{roomId}}, then {@code accessToken} is absent/null — a plain read must never
     * re-mint a fresh facilitator grant (see {@code RoomResponse}'s Javadoc).
     */
    @Test
    void findById_neverReMintsAccessToken() throws Exception {
        String roomId = createRoomFor(tokenA, "Room");

        MvcResult result = mockMvc.perform(get(BASE_PATH + "/" + roomId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode accessToken = body.get("accessToken");
        assertThat(accessToken == null || accessToken.isNull())
                .as("GET must never re-mint a facilitator grant")
                .isTrue();
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
        String id = body.get("id").asText();

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
        String roomId = createRoomFor(tokenA, "My Room");

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
        String roomId = createRoomFor(tokenA, "Tenant A Room");

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
        mockMvc.perform(get(BASE_PATH + "/" + java.util.UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a syntactically invalid (non-UUID) room id, when GET
     * /poker/rooms/{roomId} is called, then it returns HTTP 400 (Spring's path variable
     * conversion failure), never a 500.
     */
    @Test
    void findById_malformedRoomId_returns400() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/not-a-uuid")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when GET
     * /poker/rooms/{roomId} is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void findById_missingAuthorization_returns401() throws Exception {
        String roomId = createRoomFor(tokenA, "Room");

        mockMvc.perform(get(BASE_PATH + "/" + roomId))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms/join
    // -------------------------------------------------------------------------

    /**
     * Given a valid, active, non-expired invite code, when POST /poker/rooms/join is called by a
     * caller in the same tenant, then it returns HTTP 200 with the joined room's id, a non-blank
     * accessToken, and a wsTopic following the ADR-026 §2 convention — and the returned
     * accessToken is immediately usable: {@code RoomAccessGrantService#hasAccess} is true for
     * this exact (roomId, accessToken) pair right after the call, proving the grant was actually
     * issued, not just echoed in the response.
     */
    @Test
    void join_validCode_returns200WithAccessTokenAndGrantsAccess() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Sprint 8 estimation");
        String roomId = createdRoom.get("id").asText();
        String inviteCode = createdRoom.get("inviteCode").asText();

        MvcResult result = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.name").value("Sprint 8 estimation"))
                .andExpect(jsonPath("$.sequence").value("FIBONACCI"))
                .andExpect(jsonPath("$.cardValues").isArray())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.wsTopic").value("/topic/agilite/poker/" + roomId))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.facilitatorUserId").doesNotExist())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(roomAccessGrantService.hasAccess(java.util.UUID.fromString(roomId), accessToken))
                .isTrue();
    }

    /**
     * Error case: given a blank code, when POST /poker/rooms/join is called, then it returns
     * HTTP 400 with code INVALID_CODE.
     */
    @Test
    void join_blankCode_returns400WithInvalidCodeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    /**
     * Error case: given a code that is not exactly 6 characters, when POST /poker/rooms/join is
     * called, then it returns HTTP 400 with code INVALID_CODE.
     */
    @Test
    void join_wrongLengthCode_returns400WithInvalidCodeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"ABCDE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    /**
     * Error case: given a syntactically valid but unknown 6-character code, when POST
     * /poker/rooms/join is called, then it returns HTTP 404.
     */
    @Test
    void join_unknownCode_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"ZZZZZZ\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a code whose room has since expired, when POST /poker/rooms/join is
     * called, then it returns HTTP 404 — same as an unknown code, per ADR-026 §2.
     */
    @Test
    void join_expiredRoomCode_returns404() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Expired room");
        String inviteCode = createdRoom.get("inviteCode").asText();
        expireRoomByInviteCode(inviteCode);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Security: given a room created under tenant A, when a caller from tenant B attempts to
     * join it with A's valid invite code, then it returns HTTP 404 — indistinguishable from an
     * unknown code, never confirming cross-tenant existence.
     */
    @Test
    void join_crossTenantCode_returns404() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Tenant A Room");
        String inviteCode = createdRoom.get("inviteCode").asText();

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given the Authorization bearer header is absent, when POST /poker/rooms/join
     * is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void join_missingAuthorization_returns401() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms/join-anonymous (US09.3.1)
    // -------------------------------------------------------------------------

    /**
     * Given a valid, active, non-expired invite code and no {@code Authorization} header at all,
     * when POST /poker/rooms/join-anonymous is called with no pseudonym, then it returns HTTP
     * 200 with the joined room's id, a non-blank accessToken, a non-blank sessionId, a generated
     * pseudonym, a guestSessionExpiresAt, and a wsTopic — and the returned accessToken is
     * immediately usable and recognized as a guest grant, not a standard one.
     */
    @Test
    void joinAnonymous_validCode_returns200WithGuestGrantNoAuthHeaderNeeded() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Sprint 8 estimation");
        String roomId = createdRoom.get("id").asText();
        String inviteCode = createdRoom.get("inviteCode").asText();

        MvcResult result = mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(roomId))
                .andExpect(jsonPath("$.name").value("Sprint 8 estimation"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.guestSessionExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.wsTopic").value("/topic/agilite/poker/" + roomId))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = body.get("accessToken").asText();
        assertThat(body.get("pseudonym").asText()).startsWith("Invité-");
        UUID roomUuid = UUID.fromString(roomId);
        assertThat(roomAccessGrantService.hasAccess(roomUuid, accessToken)).isTrue();
        assertThat(roomAccessGrantService.isGuest(roomUuid, accessToken)).isTrue();
    }

    /**
     * Given a caller-supplied pseudonym, when POST /poker/rooms/join-anonymous is called, then
     * the response carries that exact (trimmed) pseudonym.
     */
    @Test
    void joinAnonymous_withPseudonym_returnsThatPseudonym() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();

        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + inviteCode + "\", \"pseudonym\": \"Alex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pseudonym").value("Alex"));
    }

    /**
     * Given a garbage {@code Authorization} header supplied anyway, when POST
     * /poker/rooms/join-anonymous is called, then it still succeeds with HTTP 200 — the header
     * is never read nor validated for this endpoint (US09.3.1 AC).
     */
    @Test
    void joinAnonymous_withGarbageAuthorizationHeaderAnyway_stillSucceeds() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();

        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer not-a-real-token")
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * Error case: given a syntactically valid but unknown 6-character code, when POST
     * /poker/rooms/join-anonymous is called, then it returns HTTP 404 — same unified posture as
     * the authenticated join (US09.1.2).
     */
    @Test
    void joinAnonymous_unknownCode_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"ZZZZZZ\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a code whose room has since expired, when POST
     * /poker/rooms/join-anonymous is called, then it returns HTTP 404.
     */
    @Test
    void joinAnonymous_expiredRoomCode_returns404() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Expired room");
        String inviteCode = createdRoom.get("inviteCode").asText();
        expireRoomByInviteCode(inviteCode);

        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Error case: given a blank code, when POST /poker/rooms/join-anonymous is called, then it
     * returns HTTP 400 with code INVALID_CODE.
     */
    @Test
    void joinAnonymous_blankCode_returns400WithInvalidCodeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    /**
     * Error case: given a pseudonym longer than 40 characters, when POST
     * /poker/rooms/join-anonymous is called, then it returns HTTP 400 with code
     * INVALID_PSEUDONYM.
     */
    @Test
    void joinAnonymous_tooLongPseudonym_returns400WithInvalidPseudonymCode() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();
        String longPseudonym = "a".repeat(41);

        mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"" + inviteCode + "\", \"pseudonym\": \"" + longPseudonym + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PSEUDONYM"));
    }

    // -------------------------------------------------------------------------
    // POST /poker/rooms/{roomId}/guest-sessions/heartbeat (US09.3.1)
    // -------------------------------------------------------------------------

    /**
     * Given a currently valid guest session, when the heartbeat endpoint is called with no
     * {@code Authorization} header, then it returns HTTP 200 with a refreshed expiry, and the
     * underlying access grant remains valid (proving the refresh is real, not just echoed).
     */
    @Test
    void guestHeartbeat_validSession_returns200AndRefreshesGrant() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();
        JsonNode joined = joinAnonymousNode(inviteCode, null);
        String roomId = joined.get("roomId").asText();
        String accessToken = joined.get("accessToken").asText();

        mockMvc.perform(post(BASE_PATH + "/" + roomId + "/guest-sessions/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\": \"" + accessToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        assertThat(roomAccessGrantService.hasAccess(UUID.fromString(roomId), accessToken)).isTrue();
    }

    /**
     * Error case: given an access token that was never granted, when the heartbeat endpoint is
     * called, then it returns HTTP 410 Gone with code GUEST_SESSION_EXPIRED — a clear error, not
     * a silent success.
     */
    @Test
    void guestHeartbeat_unknownAccessToken_returns410() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String roomId = createdRoom.get("id").asText();

        mockMvc.perform(post(BASE_PATH + "/" + roomId + "/guest-sessions/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessToken\": \"never-granted\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GUEST_SESSION_EXPIRED"));
    }

    /**
     * Security AC (US09.3.1): given an anonymous guest session obtained via join-anonymous, when
     * that session's access grant is checked against {@link
     * RoomAccessGrantService#requireNonGuest} — the primitive facilitator-only actions
     * (ticket creation/reveal, US09.2.1/US09.2.2, not yet built in this repo — same sprint wave,
     * no dependency on this US) are contractually required to call — then it throws {@link
     * PokerFacilitatorOnlyException}, which {@link GlobalExceptionHandler} maps to HTTP 403 with
     * code FACILITATOR_ONLY_ACTION: an anonymous session can never perform a facilitator-only
     * action, proven end-to-end from a real join response through to the HTTP mapping, never a
     * silent success.
     */
    @Test
    void anonymousSession_cannotPerformFacilitatorOnlyAction_returns403() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();
        JsonNode joined = joinAnonymousNode(inviteCode, null);
        UUID roomId = UUID.fromString(joined.get("roomId").asText());
        String accessToken = joined.get("accessToken").asText();

        assertThat(roomAccessGrantService.hasAccess(roomId, accessToken)).isTrue();
        assertThat(roomAccessGrantService.isGuest(roomId, accessToken)).isTrue();

        Throwable thrown = catchThrowable(() -> roomAccessGrantService.requireNonGuest(roomId, accessToken));
        assertThat(thrown).isInstanceOf(PokerFacilitatorOnlyException.class);

        ProblemDetail problem =
                globalExceptionHandler.handlePokerFacilitatorOnly((PokerFacilitatorOnlyException) thrown);
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getProperties()).containsEntry("code", "FACILITATOR_ONLY_ACTION");
    }

    /**
     * Security AC (US09.3.1): given an authenticated participant's own (non-guest) access grant
     * from the standard join flow (US09.1.2), when checked against {@link
     * RoomAccessGrantService#requireNonGuest}, then it does not throw — the guard never rejects
     * a real, authenticated grant.
     */
    @Test
    void authenticatedParticipantGrant_isNeverRejectedByRequireNonGuest() throws Exception {
        JsonNode createdRoom = createRoomNode(tokenA, "Room");
        String inviteCode = createdRoom.get("inviteCode").asText();

        MvcResult result = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"code\": \"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode joined = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID roomId = UUID.fromString(joined.get("roomId").asText());
        String accessToken = joined.get("accessToken").asText();

        assertThat(roomAccessGrantService.isGuest(roomId, accessToken)).isFalse();
        assertThatCode(() -> roomAccessGrantService.requireNonGuest(roomId, accessToken))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a room via the API using the given caller's bearer token and returns its id.
     *
     * @param token the caller's raw bearer token
     * @param name  the room name
     * @return the created room's UUID id, as a string
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createRoomFor(final String token, final String name) throws Exception {
        return createRoomNode(token, name).get("id").asText();
    }

    /**
     * Joins a room anonymously via the API (no {@code Authorization} header) and returns the
     * full response body, so callers can read {@code roomId}, {@code accessToken}, etc.
     *
     * @param inviteCode the room's invite code
     * @param pseudonym  the pseudonym to send, or {@code null} to omit the field entirely
     * @return the anonymous join response body
     * @throws Exception if the HTTP request fails or the response status is not 200
     */
    private JsonNode joinAnonymousNode(final String inviteCode, final String pseudonym) throws Exception {
        String body = pseudonym == null
                ? "{\"code\": \"" + inviteCode + "\"}"
                : "{\"code\": \"" + inviteCode + "\", \"pseudonym\": \"" + pseudonym + "\"}";
        MvcResult result = mockMvc.perform(post(BASE_PATH + "/join-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Creates a room via the API using the given caller's bearer token and returns the full
     * response body, so callers can read both {@code id} and {@code inviteCode}.
     *
     * @param token the caller's raw bearer token
     * @param name  the room name
     * @return the created room's full JSON response body
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private JsonNode createRoomNode(final String token, final String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Directly backdates a room's {@code expires_at} via a native update (no setter exists),
     * identified by its invite code — mirrors {@code RetroSessionControllerIT#expireSession}'s
     * pattern against the same Testcontainers Postgres instance used by this IT class.
     *
     * @param inviteCode the invite code of the room to backdate
     * @throws Exception if the JDBC update fails
     */
    private void expireRoomByInviteCode(final String inviteCode) throws Exception {
        try (var conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var ps = conn.prepareStatement(
                        "UPDATE agilite.poker_rooms SET expires_at = now() - interval '1 hour' "
                                + "WHERE invite_code = ?")) {
            ps.setString(1, inviteCode);
            ps.executeUpdate();
        }
    }
}
