package fr.pivot.agilite.retro.action;

import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RetroActionController} (US20.3.1), against the full Spring context
 * (Testcontainers PostgreSQL/Redis) — mirrors {@code RetroPhaseControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroActionControllerIT {

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
    private RetroSessionRepository sessionRepository;

    @Autowired
    private RetroCardRepository cardRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private long facilitatorId;
    private long tenantId;
    private long teamId;
    private String memberToken;
    private long memberId;
    private String otherTenantToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
        facilitatorId = facilitator.userId();
        tenantId = facilitator.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitatorId);

        memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamId, memberId);
        memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/actions
    // -------------------------------------------------------------------------

    @Test
    void create_asTeamMemberInActionPhase_returns201WithAFaireStatus() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Automate the deploy pipeline"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("A_FAIRE"))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.teamId").value(teamId))
                .andExpect(jsonPath("$.title").value("Automate the deploy pipeline"));
    }

    @Test
    void create_withOwnerAndSourceCard_returns201WithBothPersisted() throws Exception {
        String sessionId = createSession();
        String cardId = seedCard(sessionId, "went-well", "Great teamwork");
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Celebrate the team","ownerUserId":%d,"dueDate":"2026-08-01","sourceCardId":"%s"}
                                        """.formatted(memberId, cardId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerUserId").value(memberId))
                .andExpect(jsonPath("$.dueDate").value("2026-08-01"))
                .andExpect(jsonPath("$.sourceCardId").value(cardId));
    }

    @Test
    void create_sessionNotInActionPhase_returns409() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task"}
                                        """))
                .andExpect(status().isConflict());
    }

    @Test
    void create_ownerNotTeamMember_returns400() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        long outsiderId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task","ownerUserId":%d}
                                        """.formatted(outsiderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_TEAM_MEMBER"));
    }

    @Test
    void create_sourceCardFromDifferentSession_returns400() throws Exception {
        String sessionId = createSession();
        String otherSessionId = createSession();
        String cardFromOtherSession = seedCard(otherSessionId, "went-well", "Not this session");
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task","sourceCardId":"%s"}
                                        """.formatted(cardFromOtherSession)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_CARD_NOT_IN_SESSION"));
    }

    @Test
    void create_blankTitle_returns400() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":""}
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_crossTenantSession_returns404() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task"}
                                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_callerNotTeamMember_returns404() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String nonMemberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                PlatformAuthTestSupport.seedUser(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true),
                "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + nonMemberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task"}
                                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_noAuthHeader_returns401() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Task"}
                                        """))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /retro/actions/{actionId}
    // -------------------------------------------------------------------------

    @Test
    void updateStatus_asTeamMember_returns200WithNewStatus() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String actionId = createAction(sessionId, "Task");

        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"EN_COURS"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_COURS"));
    }

    @Test
    void updateStatus_reopenAbandonneeAction_returns200() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String actionId = createAction(sessionId, "Task");
        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"ABANDONNEE"}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"A_FAIRE"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("A_FAIRE"));
    }

    @Test
    void updateStatus_invalidStatusValue_returns400() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String actionId = createAction(sessionId, "Task");

        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"NOT_A_STATUS"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTION_STATUS"));
    }

    @Test
    void updateStatus_teamCallerNotMember_returns404() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String actionId = createAction(sessionId, "Task");

        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + otherTenantToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"EN_COURS"}
                                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatus_unknownAction_returns404() throws Exception {
        mockMvc.perform(
                        patch("/retro/actions/" + UUID.randomUUID())
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"EN_COURS"}
                                        """))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /retro/teams/{teamId}/actions
    // -------------------------------------------------------------------------

    @Test
    void list_includesActionsFromClosedSession() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        createAction(sessionId, "From an active session");
        advancePhase(sessionId, RetroPhase.CLOSED);

        String secondSessionId = createSession();
        advancePhase(secondSessionId, RetroPhase.ACTION);
        createAction(secondSessionId, "From another session");

        mockMvc.perform(
                        get("/retro/teams/" + teamId + "/actions")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void list_withStatusFilter_returnsOnlyMatching() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        String actionId = createAction(sessionId, "Task A");
        createAction(sessionId, "Task B");
        mockMvc.perform(
                        patch("/retro/actions/" + actionId)
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"status":"TERMINEE"}
                                        """))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/retro/teams/" + teamId + "/actions")
                                .param("status", "TERMINEE")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("TERMINEE"));
    }

    @Test
    void list_invalidStatusFilter_returns400() throws Exception {
        mockMvc.perform(
                        get("/retro/teams/" + teamId + "/actions")
                                .param("status", "NOT_A_STATUS")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTION_STATUS"));
    }

    @Test
    void list_callerNotTeamMember_returns404() throws Exception {
        mockMvc.perform(
                        get("/retro/teams/" + teamId + "/actions")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_unknownTeam_returns404() throws Exception {
        mockMvc.perform(
                        get("/retro/teams/999999999/actions")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private void advancePhase(final String sessionId, final RetroPhase phase) {
        RetroSession session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        session.setCurrentPhase(phase);
        sessionRepository.save(session);
    }

    private String seedCard(final String sessionId, final String columnKey, final String content) {
        RetroCard card = cardRepository.save(new RetroCard(
                UUID.fromString(sessionId), columnKey, content, false, facilitatorId, Instant.now()));
        return card.getId().toString();
    }

    private String createAction(final String sessionId, final String title) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/actions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"%s"}
                                        """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
