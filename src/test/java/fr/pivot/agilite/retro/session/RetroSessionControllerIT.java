package fr.pivot.agilite.retro.session;

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
 * Integration tests for {@link RetroSessionController} exercising the full Spring context
 * against a real PostgreSQL database (and Redis) provided by Testcontainers (US20.1.1).
 *
 * <p>Covers the Gate-1 acceptance criteria in {@code us-creer-retro.md}: session creation
 * (with facilitator/phase/joinCode invariants), the authenticated detail endpoint (any phase,
 * including {@code CLOSED}), the public join-resolution endpoint (410 on expiry/closure), and
 * every documented error case (400/403/404/410/401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroSessionControllerIT {

    private static final String SESSIONS_PATH = "/retro/sessions";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties to the Spring context, and seeds the
     * {@code public} schema (owned by {@code pivot-core}) before the Spring context and its
     * Flyway run start.
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
    private RetroSessionRepository sessionRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String memberToken;
    private long memberId;
    private long teamId;
    private long tenantId;
    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds a tenant with a team, a member of that team, and a user
     * belonging to an entirely separate tenant (for cross-tenant assertions) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture member = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        memberToken = member.rawToken();
        memberId = member.userId();
        tenantId = member.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, memberId);

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions
    // -------------------------------------------------------------------------

    /**
     * Given an authenticated team member, when POST /retro/sessions is called with title,
     * format, teamId, then a session is created with the caller as facilitator, phase
     * CONTRIBUTION, and a unique 6-character alphanumeric join code — and the response exposes
     * every documented field.
     */
    @Test
    void create_asTeamMember_returns201WithFacilitatorAndJoinCode() throws Exception {
        MvcResult result = mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Sprint 12 Retro","format":"START_STOP_CONTINUE","teamId":%d,
                                         "sprintRef":"SPRINT-12"}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.title").value("Sprint 12 Retro"))
                .andExpect(jsonPath("$.format").value("START_STOP_CONTINUE"))
                .andExpect(jsonPath("$.teamId").value(teamId))
                .andExpect(jsonPath("$.facilitatorUserId").value(memberId))
                .andExpect(jsonPath("$.currentPhase").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.voteCountPerParticipant").value(3))
                .andExpect(jsonPath("$.sprintRef").value("SPRINT-12"))
                .andExpect(jsonPath("$.joinCode").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("joinCode").asText()).matches("[A-Z0-9]{6}");
    }

    /**
     * Given a request with only the mandatory fields, when POST /retro/sessions is called,
     * then optional fields are null (timers) or defaulted (voteCountPerParticipant = 3).
     */
    @Test
    void create_withoutOptionalFields_defaultsVoteCountAndNullTimers() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"CUSTOM","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contributionTimerSeconds").doesNotExist())
                .andExpect(jsonPath("$.voteTimerSeconds").doesNotExist())
                .andExpect(jsonPath("$.actionTimerSeconds").doesNotExist())
                .andExpect(jsonPath("$.voteCountPerParticipant").value(3));
    }

    /**
     * Given a {@code teamId} that does not exist, when POST /retro/sessions is called,
     * then it returns 404.
     */
    @Test
    void create_unknownTeamId_returns404() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"START_STOP_CONTINUE","teamId":999999999}
                                        """))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a {@code teamId} belonging to a different tenant than the caller, when POST
     * /retro/sessions is called, then it returns 404 (never confirms cross-tenant existence).
     */
    @Test
    void create_teamFromDifferentTenant_returns404() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a {@code teamId} that exists in the caller's tenant, but the caller is not a
     * member, when POST /retro/sessions is called, then it returns 403.
     */
    @Test
    void create_nonMemberOfExistingTeamInSameTenant_returns403() throws Exception {
        long strangerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String strangerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                strangerId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + strangerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isForbidden());
    }

    /**
     * Given a {@code format} outside the 5-value catalogue, when POST /retro/sessions is
     * called, then it returns 400.
     */
    @Test
    void create_invalidFormat_returns400() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"NOT_A_FORMAT","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a blank title, when POST /retro/sessions is called, then it returns 400.
     */
    @Test
    void create_blankTitle_returns400() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a title longer than 100 characters, when POST /retro/sessions is called,
     * then it returns 400.
     */
    @Test
    void create_titleTooLong_returns400() throws Exception {
        String longTitle = "a".repeat(101);
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"%s","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(longTitle, teamId)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a zero or negative timer value, when POST /retro/sessions is called,
     * then it returns 400.
     */
    @Test
    void create_zeroTimer_returns400() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"START_STOP_CONTINUE","teamId":%d,
                                         "contributionTimerSeconds":0}
                                        """.formatted(teamId)))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given no {@code Authorization} header, when POST /retro/sessions is called,
     * then it returns 401.
     */
    @Test
    void create_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(
                        post(SESSIONS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /retro/sessions/{id}
    // -------------------------------------------------------------------------

    /**
     * Given a session created by the caller's tenant, when GET /retro/sessions/{id} is called
     * by an authenticated member of that tenant, then it returns 200 with the full detail.
     */
    @Test
    void findById_sameTenant_returns200WithFullDetail() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro A");

        mockMvc.perform(
                        get(SESSIONS_PATH + "/" + sessionId)
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.title").value("Retro A"))
                .andExpect(jsonPath("$.joinCode").isString());
    }

    /**
     * Given a session belonging to a different tenant, when GET /retro/sessions/{id} is
     * called, then it returns 404 (cross-tenant, never confirmed via 403).
     */
    @Test
    void findById_crossTenant_returns404() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro B");

        mockMvc.perform(
                        get(SESSIONS_PATH + "/" + sessionId)
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given an unknown session id, when GET /retro/sessions/{id} is called,
     * then it returns 404.
     */
    @Test
    void findById_unknownId_returns404() throws Exception {
        mockMvc.perform(
                        get(SESSIONS_PATH + "/" + UUID.randomUUID())
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a session whose phase has been advanced to CLOSED, when GET /retro/sessions/{id}
     * is called, then it still returns 200 with the full detail — non-regression guard for the
     * join-vs-read distinction (US20.1.2c: a closed session stays readable).
     */
    @Test
    void findById_closedSession_returns200WithFullDetail() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro C");
        closeSession(UUID.fromString(sessionId));

        mockMvc.perform(
                        get(SESSIONS_PATH + "/" + sessionId)
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CLOSED"));
    }

    /**
     * Given an expired session (still {@code CONTRIBUTION} phase), when GET
     * /retro/sessions/{id} is called, then it still returns 200 with the full detail — the
     * 410 gate applies only to join-code resolution, never here.
     */
    @Test
    void findById_expiredSession_returns200WithFullDetail() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro D");
        expireSession(UUID.fromString(sessionId));

        mockMvc.perform(
                        get(SESSIONS_PATH + "/" + sessionId)
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CONTRIBUTION"));
    }

    /**
     * Given no {@code Authorization} header, when GET /retro/sessions/{id} is called,
     * then it returns 401.
     */
    @Test
    void findById_noAuthHeader_returns401() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro E");

        mockMvc.perform(get(SESSIONS_PATH + "/" + sessionId))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /retro/sessions/join/{joinCode}
    // -------------------------------------------------------------------------

    /**
     * Given a joinable session's code, when GET /retro/sessions/join/{joinCode} is called
     * with NO Authorization header, then it returns 200 with only the minimal public fields —
     * confirming the frictionless, unauthenticated join contract.
     */
    @Test
    void findByJoinCode_noAuthRequired_returns200WithMinimalFields() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro F");
        String joinCode = joinCodeOf(sessionId);

        mockMvc.perform(get(SESSIONS_PATH + "/join/" + joinCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.title").value("Retro F"))
                .andExpect(jsonPath("$.format").value("START_STOP_CONTINUE"))
                .andExpect(jsonPath("$.currentPhase").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.teamId").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.facilitatorUserId").doesNotExist())
                .andExpect(jsonPath("$.joinCode").doesNotExist());
    }

    /**
     * Given an unknown join code, when GET /retro/sessions/join/{joinCode} is called,
     * then it returns 404.
     */
    @Test
    void findByJoinCode_unknownCode_returns404() throws Exception {
        mockMvc.perform(get(SESSIONS_PATH + "/join/ZZZZZZ"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a session whose {@code expiresAt} has passed, when GET
     * /retro/sessions/join/{joinCode} is called, then it returns 410 Gone.
     */
    @Test
    void findByJoinCode_expiredSession_returns410() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro G");
        String joinCode = joinCodeOf(sessionId);
        expireSession(UUID.fromString(sessionId));

        mockMvc.perform(get(SESSIONS_PATH + "/join/" + joinCode))
                .andExpect(status().isGone());
    }

    /**
     * Given a session whose phase is CLOSED, when GET /retro/sessions/join/{joinCode} is
     * called, then it returns 410 Gone.
     */
    @Test
    void findByJoinCode_closedSession_returns410() throws Exception {
        String sessionId = createSession(memberToken, teamId, "Retro H");
        String joinCode = joinCodeOf(sessionId);
        closeSession(UUID.fromString(sessionId));

        mockMvc.perform(get(SESSIONS_PATH + "/join/" + joinCode))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a session via the API using the given caller's bearer token and returns its id. */
    private String createSession(final String token, final long teamId, final String title) throws Exception {
        MvcResult result = mockMvc.perform(
                        post(SESSIONS_PATH)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"%s","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(title, teamId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    /** Reads back the join code of a created session directly via the repository. */
    private String joinCodeOf(final String sessionId) {
        return sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow().getJoinCode();
    }

    /** Directly advances a session's phase to CLOSED via the repository (no API for it yet). */
    private void closeSession(final UUID sessionId) {
        RetroSession session = sessionRepository.findById(sessionId).orElseThrow();
        session.setCurrentPhase(RetroPhase.CLOSED);
        sessionRepository.save(session);
    }

    /** Directly backdates a session's {@code expiresAt} via a native update (no setter exists). */
    private void expireSession(final UUID sessionId) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var ps = conn.prepareStatement(
                        "UPDATE agilite.retro_sessions SET expires_at = now() - interval '1 hour' "
                                + "WHERE id = ?::uuid")) {
            ps.setString(1, sessionId.toString());
            ps.executeUpdate();
        }
    }
}
