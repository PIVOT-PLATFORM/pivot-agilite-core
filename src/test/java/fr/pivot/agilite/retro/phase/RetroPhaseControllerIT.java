package fr.pivot.agilite.retro.phase;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RetroPhaseController} — facilitator-triggered manual contribution
 * close and reveal (US20.1.2a), against the full Spring context (Testcontainers PostgreSQL/Redis).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroPhaseControllerIT {

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

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/contribution/close
    // -------------------------------------------------------------------------

    @Test
    void closeContribution_asFacilitator_transitionsToRevue() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("REVUE"));
    }

    @Test
    void closeContribution_asNonFacilitatorMember_returns403() throws Exception {
        String sessionId = createSession();
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeContribution_crossTenant_returns404() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void closeContribution_alreadyClosed_returns409() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void closeContribution_noAuthHeader_returns401() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(post("/retro/sessions/" + sessionId + "/contribution/close"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/reveal
    // -------------------------------------------------------------------------

    @Test
    void reveal_asFacilitatorInRevuePhase_returnsCardsGroupedByColumn() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);
        seedCard(sessionId, "went-well", "Good pace");
        seedCard(sessionId, "went-well", "Great teamwork");
        seedCard(sessionId, "to-improve", "Too many meetings");

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardCount").value(3))
                .andExpect(jsonPath("$.columns['went-well'].length()").value(2))
                .andExpect(jsonPath("$.columns['to-improve'].length()").value(1))
                .andExpect(jsonPath("$.columns['went-well'][0].content").isString());
    }

    @Test
    void reveal_stillInContribution_returns409() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void reveal_asNonFacilitator_returns403() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
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

    private void seedCard(final String sessionId, final String columnKey, final String content) {
        cardRepository.save(new RetroCard(
                UUID.fromString(sessionId), columnKey, content, false, facilitatorId, Instant.now()));
    }
}
