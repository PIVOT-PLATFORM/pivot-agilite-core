package fr.pivot.agilite.wheel;

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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the weighted anti-repeat draw endpoints (US14.2.1) —
 * {@code POST /wheels/{wheelId}/spin} and {@code GET /wheels/{wheelId}/draws} — exercising the
 * full Spring context against a real PostgreSQL database provided by Testcontainers.
 *
 * <p>Statistical distribution assertions are covered separately, without Testcontainers, by
 * {@link WeightedEntrySelectorTest}; this class focuses on wiring, persistence, and the tenant/
 * team-membership isolation convention already established by {@link WheelControllerIT}
 * (US14.1.1).
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths below start with {@code
 * /wheels}, not {@code /api/agilite/...} (same convention as {@link WheelControllerIT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WheelSpinControllerIT {

    private static final String BASE_PATH = "/wheels";

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

    private long teamA;
    private String tokenA1;

    private long teamB;
    private String tokenB;

    /**
     * Sets up MockMvc from the web application context and seeds two distinct tenant/team/user/
     * token fixtures (A and B) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamA = fixtureA.teamId();
        tokenA1 = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamB = fixtureB.teamId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /wheels/{wheelId}/spin
    // -------------------------------------------------------------------------

    @Test
    void spin_onWheelWithSingleEntry_returns201WithThatEntryAndDefaultMode() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Une seule entree",
                "[{\"type\": \"free_text\", \"label\": \"Solo\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wheelId").value(wheelId))
                .andExpect(jsonPath("$.label").value("Solo"))
                .andExpect(jsonPath("$.entryId").isNotEmpty())
                .andExpect(jsonPath("$.drawnAt").isNotEmpty())
                .andExpect(jsonPath("$.antiRepeatMode").value("reduced_weight"));
    }

    @Test
    void spin_updatesWheelsLastDrawnEntryId() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Marqueur",
                "[{\"type\": \"free_text\", \"label\": \"Solo\"}]");

        MvcResult spinResult = mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isCreated())
                .andReturn();
        String entryId = objectMapper.readTree(spinResult.getResponse().getContentAsString())
                .get("entryId").asText();

        mockMvc.perform(get(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastDrawnEntryId").value(entryId));
    }

    @Test
    void spin_withExplicitAntiRepeatMode_isReflectedInResponse() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Mode explicite",
                "[{\"type\": \"free_text\", \"label\": \"A\"}, {\"type\": \"free_text\", \"label\": \"B\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"antiRepeatMode\": \"exclude\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.antiRepeatMode").value("exclude"));
    }

    @Test
    void spin_withInvalidAntiRepeatMode_returns400WithInvalidAntiRepeatModeCode() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Mode invalide",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"antiRepeatMode\": \"aleatoire\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANTI_REPEAT_MODE"));
    }

    @Test
    void spin_onWheelWithNoEntries_returns409WithEmptyWheelCode() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Bientot vide",
                "[{\"type\": \"free_text\", \"label\": \"Ephemere\"}]");
        deleteAllEntriesDirectly(wheelId);

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPTY_WHEEL"));
    }

    @Test
    void spin_crossTenant_returns404() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Roue A",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void spin_missingAuthorizationHeader_returns401() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Roue A",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void spin_onUnknownWheel_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + java.util.UUID.randomUUID() + "/spin")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /wheels/{wheelId}/draws
    // -------------------------------------------------------------------------

    @Test
    void draws_withNoDrawYet_returnsEmptyList() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Jamais tiree",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void draws_afterSpin_includesTheNewDrawFirst() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Historique",
                "[{\"type\": \"free_text\", \"label\": \"Solo\"}]");

        mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Solo"))
                .andExpect(jsonPath("$[0].drawnAt").isNotEmpty());
    }

    @Test
    void draws_withLimitParam_capsResultCount() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Plusieurs tirages",
                "[{\"type\": \"free_text\", \"label\": \"Solo\"}]");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(BASE_PATH + "/" + wheelId + "/spin")
                            .header("Authorization", "Bearer " + tokenA1))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void draws_withNonIntegerLimit_returns400WithInvalidLimitCode() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Limite invalide",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .param("limit", "beaucoup")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void draws_withLimitOutOfRange_returns400WithInvalidLimitCode() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Limite hors bornes",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .param("limit", "0")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test
    void draws_crossTenant_returns404() throws Exception {
        String wheelId = createWheelWithEntries(tokenA1, teamA, "Roue A",
                "[{\"type\": \"free_text\", \"label\": \"A\"}]");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId + "/draws")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a wheel via the API with the given raw {@code entries} JSON array and returns its
     * identifier.
     *
     * @param token   the caller's raw bearer token
     * @param teamId  the owning team's id
     * @param name    the wheel name
     * @param entries a raw JSON array literal for the {@code entries} field
     * @return the string representation of the created wheel's UUID
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createWheelWithEntries(
            final String token, final long teamId, final String name, final String entries) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"teamId\": " + teamId + ", \"name\": \"" + name + "\", \"entries\": " + entries
                                + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    /**
     * Deletes every {@code agilite.wheel_entry} row of a wheel directly via JDBC, bypassing the
     * API — simulates the otherwise-unreachable "empty wheel" state that {@code POST /spin}'s
     * defensive {@code EMPTY_WHEEL} guard exists for (US14.1.1's {@code PUT}/{@code POST} always
     * reject an empty {@code entries} list, so this state can never occur through normal use).
     *
     * @param wheelId the wheel's identifier (string form of its UUID)
     * @throws Exception if the JDBC operation fails
     */
    private void deleteAllEntriesDirectly(final String wheelId) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement("DELETE FROM agilite.wheel_entry WHERE wheel_id = ?::uuid")) {
            ps.setString(1, wheelId);
            ps.executeUpdate();
        }
    }
}
