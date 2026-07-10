package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.retro.session.RetroPhase;
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

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving {@link RetroPhaseScheduler}'s timer-based auto-transition against the
 * full Spring context and a real, running {@code @Scheduled} task (US20.1.2a AC: "when le timer
 * configuré expire, then la phase passe automatiquement à REVUE et un événement PHASE_CHANGED est
 * diffusé").
 *
 * <p>Uses a 1-second {@code contributionTimerSeconds} against the much shorter scheduler poll
 * interval configured in {@code application-test.yml} (200ms) so the assertion converges quickly
 * via {@link org.awaitility.Awaitility}, never a raw {@code Thread.sleep} guess.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroPhaseSchedulerIT {

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

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private long teamId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
        long tenantId = facilitator.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitator.userId());
    }

    /**
     * Given a session created with a 1-second contribution timer, when that timer elapses, then
     * the scheduler auto-transitions it to REVUE without any manual action.
     */
    @Test
    void contributionTimerExpiry_autoTransitionsToRevue() throws Exception {
        String sessionId = createSessionWithContributionTimer(1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            RetroPhase phase = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow().getCurrentPhase();
            assertThat(phase).isEqualTo(RetroPhase.REVUE);
        });
    }

    /**
     * Given a session created with NO configured contribution timer (manual closure only), when
     * time passes, then the scheduler never auto-transitions it — it stays in CONTRIBUTION.
     */
    @Test
    void noConfiguredTimer_neverAutoTransitions() throws Exception {
        String sessionId = createSessionWithoutTimer();

        Thread.sleep(1500);

        RetroPhase phase = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow().getCurrentPhase();
        assertThat(phase).isEqualTo(RetroPhase.CONTRIBUTION);
    }

    private String createSessionWithContributionTimer(final int timerSeconds) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/retro/sessions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Timed Retro","format":"START_STOP_CONTINUE","teamId":%d,
                                         "contributionTimerSeconds":%d}
                                        """.formatted(teamId, timerSeconds)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createSessionWithoutTimer() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/retro/sessions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Untimed Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
