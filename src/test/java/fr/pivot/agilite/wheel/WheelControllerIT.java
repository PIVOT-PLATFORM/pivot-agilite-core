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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WheelController} exercising the full Spring context against a
 * real PostgreSQL database and Redis provided by Testcontainers (US14.1.1).
 *
 * <p>Covers the backlog AC (creation with team-member and free-text entries, default/explicit
 * weight, list/get/update/delete, all error cases, and tenant/team-membership isolation). Each
 * test authenticates via real bearer tokens issued for tenants/users/teams seeded through
 * {@link PlatformAuthTestSupport} (US14.1.1) — tenant and team-membership isolation is exercised
 * with distinct seeded identities.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix. Paths used in tests therefore start
 * with {@code /wheels} (not {@code /api/agilite/...}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WheelControllerIT {

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

    private long tenantA;
    private long teamA;
    private long teamMemberA1;
    private String tokenA1;

    private long tenantB;
    private long teamB;
    private String tokenB;

    /**
     * Sets up MockMvc from the web application context and seeds two distinct
     * tenant/team/user/token fixtures (A and B) before each test, plus a second member of team A.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA1.tenantId();
        teamA = fixtureA1.teamId();
        teamMemberA1 = fixtureA1.teamMemberId();
        tokenA1 = fixtureA1.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        teamB = fixtureB.teamId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /wheels
    // -------------------------------------------------------------------------

    @Test
    void createWheel_withTeamMemberEntry_returns201WithResolvedLabel() throws Exception {
        long userId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantA, true, "Ada", "Lovelace");
        long teamMemberId = PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userId);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Retro roue\", "
                                + "\"entries\": [{\"type\": \"team_member\", \"teamMemberId\": " + teamMemberId + "}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Retro roue"))
                .andExpect(jsonPath("$.teamId").value(teamA))
                .andExpect(jsonPath("$.tenantId").value(tenantA))
                .andExpect(jsonPath("$.lastDrawnEntryId").doesNotExist())
                .andExpect(jsonPath("$.entries[0].type").value("team_member"))
                .andExpect(jsonPath("$.entries[0].label").value("Ada Lovelace"))
                .andExpect(jsonPath("$.entries[0].weight").value(1));
    }

    @Test
    void createWheel_withFreeTextEntry_returns201WithRawLabel() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Roue libre\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"Invité externe\", \"weight\": 5}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entries[0].type").value("free_text"))
                .andExpect(jsonPath("$.entries[0].teamMemberId").doesNotExist())
                .andExpect(jsonPath("$.entries[0].label").value("Invité externe"))
                .andExpect(jsonPath("$.entries[0].weight").value(5));
    }

    @Test
    void createWheel_withEmptyEntries_returns400WithEmptyEntriesCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Vide\", \"entries\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_ENTRIES"));
    }

    @Test
    void createWheel_withBlankName_returns400WithInvalidNameCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"X\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    @Test
    void createWheel_withDuplicateTeamMemberEntry_returns400WithDuplicateEntryCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Doublon\", \"entries\": ["
                                + "{\"type\": \"team_member\", \"teamMemberId\": " + teamMemberA1 + "},"
                                + "{\"type\": \"team_member\", \"teamMemberId\": " + teamMemberA1 + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ENTRY"));
    }

    @Test
    void createWheel_withDuplicateFreeTextLabelCaseInsensitive_returns400WithDuplicateEntryCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Doublon libre\", \"entries\": ["
                                + "{\"type\": \"free_text\", \"label\": \"Alice\"},"
                                + "{\"type\": \"free_text\", \"label\": \"  alice \"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ENTRY"));
    }

    @Test
    void createWheel_withTeamMemberFromAnotherTeam_returns400WithInvalidEntryTeamMemberCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"teamId\": " + teamB + ", \"name\": \"Cross\", "
                                + "\"entries\": [{\"type\": \"team_member\", \"teamMemberId\": " + teamMemberA1 + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_TEAM_MEMBER"));
    }

    @Test
    void createWheel_withWeightOutOfRange_returns400WithInvalidWeightCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Poids invalide\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"X\", \"weight\": 11}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEIGHT"));
    }

    @Test
    void createWheel_forUnknownTeam_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": 999999999, \"name\": \"X\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"X\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWheel_forTeamOfAnotherTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamB + ", \"name\": \"X\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"X\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createWheel_missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"X\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"X\"}]}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /wheels?teamId=
    // -------------------------------------------------------------------------

    @Test
    void listWheels_returnsWheelsOfTeam() throws Exception {
        createWheelFor(tokenA1, teamA, "Roue A1");
        createWheelFor(tokenA1, teamA, "Roue A2");

        mockMvc.perform(get(BASE_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listWheels_nonMemberOfTeam_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /wheels/{wheelId}
    // -------------------------------------------------------------------------

    @Test
    void findById_whenMember_returnsWheel() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "Ma roue");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(wheelId))
                .andExpect(jsonPath("$.name").value("Ma roue"));
    }

    @Test
    void findById_crossTenant_returns404() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "Tenant A");

        mockMvc.perform(get(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PUT /wheels/{wheelId}
    // -------------------------------------------------------------------------

    @Test
    void updateWheel_replacesEntriesEntirely() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "À remplacer");

        mockMvc.perform(put(BASE_PATH + "/" + wheelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Renommée\", \"entries\": ["
                                + "{\"type\": \"free_text\", \"label\": \"Nouvel entrant\", \"weight\": 3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renommée"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].label").value("Nouvel entrant"))
                .andExpect(jsonPath("$.entries[0].weight").value(3));
    }

    @Test
    void updateWheel_withEmptyEntries_returns400() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "À vider");

        mockMvc.perform(put(BASE_PATH + "/" + wheelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Renommée\", \"entries\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_ENTRIES"));
    }

    @Test
    void updateWheel_crossTenant_returns404() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "Titre");

        mockMvc.perform(put(BASE_PATH + "/" + wheelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"name\": \"Piraté\", \"entries\": [{\"type\": \"free_text\", \"label\": \"X\"}]}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /wheels/{wheelId}
    // -------------------------------------------------------------------------

    @Test
    void deleteWheel_whenMember_returns204AndWheelIsGone() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "À supprimer");

        mockMvc.perform(delete(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteWheel_crossTenant_returns404() throws Exception {
        String wheelId = createWheelFor(tokenA1, teamA, "Titre");

        mockMvc.perform(delete(BASE_PATH + "/" + wheelId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a wheel via the API using the given caller's bearer token and returns its
     * identifier.
     *
     * @param token  the caller's raw bearer token
     * @param teamId the owning team's id
     * @param name   the wheel name
     * @return the string representation of the created wheel's UUID
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createWheelFor(final String token, final long teamId, final String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"teamId\": " + teamId + ", \"name\": \"" + name + "\", "
                                + "\"entries\": [{\"type\": \"free_text\", \"label\": \"Entrant\"}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }
}
