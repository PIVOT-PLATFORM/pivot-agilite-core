package fr.pivot.agilite.retro.ws;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RetroSessionAccessController} — the frictionless join/access-
 * grant endpoint (US20.1.2a), exercising the full Spring context against real PostgreSQL/Redis
 * (Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroSessionAccessControllerIT {

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
    private RetroAccessGrantService grantService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private long facilitatorId;
    private long tenantId;
    private long teamId;

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
    }

    /**
     * Given the session's own facilitator, when they join with their bearer token, then the
     * grant is marked facilitator and both destinations are exposed.
     */
    @Test
    void join_asFacilitator_returnsFacilitatorGrant() throws Exception {
        String sessionId = createSession();

        MvcResult result = mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/participants")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.facilitator").value(true))
                .andExpect(jsonPath("$.facilitatorTopicDestination").isString())
                .andExpect(jsonPath("$.topicDestination").value("/topic/agilite/retro/" + sessionId))
                .andExpect(jsonPath("$.submitDestination").value("/app/agilite/retro/" + sessionId + "/cards"))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = node.get("accessToken").asText();
        assertThat(grantService.resolveGrant(UUID.fromString(sessionId), token)).isPresent();
        assertThat(grantService.resolveGrant(UUID.fromString(sessionId), token).get().facilitator()).isTrue();
    }

    /**
     * Given a non-facilitator member of the same tenant, when they join, then the grant is not
     * marked facilitator and no facilitator topic is exposed.
     */
    @Test
    void join_asNonFacilitatorMember_returnsNonFacilitatorGrant() throws Exception {
        String sessionId = createSession();
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", java.time.Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/participants")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facilitator").value(false))
                .andExpect(jsonPath("$.facilitatorTopicDestination").doesNotExist());
    }

    /**
     * Given no {@code Authorization} header at all, when a participant joins, then it still
     * succeeds as an anonymous participant — the frictionless join-by-code design pillar.
     */
    @Test
    void join_noAuthHeader_returns201AsAnonymousParticipant() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(post("/retro/sessions/" + sessionId + "/participants"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.facilitator").value(false));
    }

    /**
     * Given a bearer token belonging to a different tenant than the session's own, when that
     * caller joins, then they are still granted access, but as an anonymous participant (never
     * cross-tenant attributed).
     */
    @Test
    void join_crossTenantToken_returns201AsAnonymousParticipant() throws Exception {
        String sessionId = createSession();
        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        mockMvc.perform(
                        post("/retro/sessions/" + sessionId + "/participants")
                                .header("Authorization", "Bearer " + otherTenant.rawToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.facilitator").value(false));
    }

    /**
     * Given an unknown session id, when a join is attempted, then it returns 404.
     */
    @Test
    void join_unknownSession_returns404() throws Exception {
        mockMvc.perform(post("/retro/sessions/" + UUID.randomUUID() + "/participants"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a session already CLOSED, when a new join is attempted, then it returns 410 Gone.
     */
    @Test
    void join_closedSession_returns410() throws Exception {
        String sessionId = createSession();
        RetroSession session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        session.setCurrentPhase(RetroPhase.CLOSED);
        sessionRepository.save(session);

        mockMvc.perform(post("/retro/sessions/" + sessionId + "/participants"))
                .andExpect(status().isGone());
    }

    /** Creates a session via the API using the facilitator's bearer token and returns its id. */
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
}
