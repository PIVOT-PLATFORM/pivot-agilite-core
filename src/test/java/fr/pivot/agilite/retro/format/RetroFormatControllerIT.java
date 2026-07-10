package fr.pivot.agilite.retro.format;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RetroFormatController} exercising the full Spring context against
 * a real PostgreSQL database (and Redis) provided by Testcontainers (US20.2.1).
 *
 * <p>Covers the Gate-1 acceptance criteria in {@code us-formats-retro.md}: the format catalogue
 * listing (system formats always present, custom formats scoped to the caller's tenant),
 * custom-format creation (happy path, column-count/label-length boundaries, slug generation),
 * and the structural-immutability guarantee (no {@code PUT}/{@code PATCH}/{@code DELETE} route
 * exists on {@code /retro/formats/{key}} at all).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class RetroFormatControllerIT {

    private static final String FORMATS_PATH = "/retro/formats";

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

    private MockMvc mockMvc;

    private String memberToken;
    private String otherTenantToken;

    /**
     * Sets up MockMvc and seeds a tenant with an authenticated member, and a user belonging to
     * an entirely separate tenant (for cross-tenant assertions) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture member = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        memberToken = member.rawToken();

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // GET /retro/formats
    // -------------------------------------------------------------------------

    /**
     * Given an authenticated caller with no custom formats, when GET /retro/formats is called,
     * then it returns exactly the 4 system formats, in fixed order, with their full column
     * shape.
     */
    @Test
    void list_withNoCustomFormats_returnsFourSystemFormatsInFixedOrder() throws Exception {
        mockMvc.perform(get(FORMATS_PATH).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats.length()").value(4))
                .andExpect(jsonPath("$.formats[0].key").value("START_STOP_CONTINUE"))
                .andExpect(jsonPath("$.formats[0].system").value(true))
                .andExpect(jsonPath("$.formats[0].columns.length()").value(3))
                .andExpect(jsonPath("$.formats[0].columns[0].key").value("START"))
                .andExpect(jsonPath("$.formats[0].columns[0].color").value("#2E7D32"))
                .andExpect(jsonPath("$.formats[0].columns[0].icon").value("play_arrow"))
                .andExpect(jsonPath("$.formats[1].key").value("KIF_KAF"))
                .andExpect(jsonPath("$.formats[2].key").value("FOUR_L"))
                .andExpect(jsonPath("$.formats[3].key").value("MAD_SAD_GLAD"));
    }

    /**
     * Given a caller's own custom format, when GET /retro/formats is called, then it appears
     * after the 4 system formats, but a different tenant's custom format never appears —
     * tenant isolation is enforced.
     */
    @Test
    void list_withOwnCustomFormat_appendsItButNeverAnotherTenants() throws Exception {
        createCustomFormat(memberToken, "My Team Format", """
                [{"label":"Bien"},{"label":"Mal"}]
                """);
        createCustomFormat(otherTenantToken, "Other Tenant Format", """
                [{"label":"Un"},{"label":"Deux"}]
                """);

        mockMvc.perform(get(FORMATS_PATH).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats.length()").value(5))
                .andExpect(jsonPath("$.formats[4].label").value("My Team Format"))
                .andExpect(jsonPath("$.formats[4].system").value(false));
    }

    /**
     * Given no {@code Authorization} header, when GET /retro/formats is called, then it returns
     * 401 — this endpoint is never public, unlike {@code GET /retro/sessions/join/{joinCode}}.
     */
    @Test
    void list_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(get(FORMATS_PATH)).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /retro/formats
    // -------------------------------------------------------------------------

    /**
     * Given a valid label and 2 columns with full column detail, when POST /retro/formats is
     * called, then it returns 201 with a server-generated UUID key, {@code system = false}, and
     * every column's data echoed back, keys slugged from labels.
     */
    @Test
    void create_withFullColumnDetail_returns201WithSluggedKeys() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Notre format équipe","columns":[
                                          {"label":"Bien","color":"#2E7D32","description":"Ce qui va bien","icon":"thumb_up"},
                                          {"label":"Mal"}
                                        ]}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").isString())
                .andExpect(jsonPath("$.label").value("Notre format équipe"))
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.columns[0].key").value("BIEN"))
                .andExpect(jsonPath("$.columns[0].color").value("#2E7D32"))
                .andExpect(jsonPath("$.columns[0].description").value("Ce qui va bien"))
                .andExpect(jsonPath("$.columns[0].icon").value("thumb_up"))
                .andExpect(jsonPath("$.columns[1].key").value("MAL"))
                .andExpect(jsonPath("$.columns[1].color").value("#C62828"))
                .andExpect(jsonPath("$.columns[1].description").doesNotExist())
                .andExpect(jsonPath("$.columns[1].icon").doesNotExist());
    }

    /**
     * Given 0 columns (the exact "format CUSTOM sans colonnes définies" AC case), when POST
     * /retro/formats is called, then it returns 400 with code {@code
     * CUSTOM_FORMAT_INVALID_COLUMN_COUNT}.
     */
    @Test
    void create_withZeroColumns_returns400() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOM_FORMAT_INVALID_COLUMN_COUNT"));
    }

    /**
     * Given exactly 1 column, when POST /retro/formats is called, then it returns 400 with code
     * {@code CUSTOM_FORMAT_INVALID_COLUMN_COUNT} — the lower boundary is 2, not 1.
     */
    @Test
    void create_withOneColumn_returns400() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":"Seule"}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOM_FORMAT_INVALID_COLUMN_COUNT"));
    }

    /**
     * Given exactly 2 columns (the lower boundary), when POST /retro/formats is called, then it
     * returns 201.
     */
    @Test
    void create_withTwoColumns_returns201() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":"Un"},{"label":"Deux"}]}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columns.length()").value(2));
    }

    /**
     * Given exactly 8 columns (the upper boundary), when POST /retro/formats is called, then it
     * returns 201.
     */
    @Test
    void create_withEightColumns_returns201() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[
                                          {"label":"C1"},{"label":"C2"},{"label":"C3"},{"label":"C4"},
                                          {"label":"C5"},{"label":"C6"},{"label":"C7"},{"label":"C8"}
                                        ]}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columns.length()").value(8));
    }

    /**
     * Given 9 columns (past the upper boundary), when POST /retro/formats is called, then it
     * returns 400 with code {@code CUSTOM_FORMAT_INVALID_COLUMN_COUNT}.
     */
    @Test
    void create_withNineColumns_returns400() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[
                                          {"label":"C1"},{"label":"C2"},{"label":"C3"},{"label":"C4"},
                                          {"label":"C5"},{"label":"C6"},{"label":"C7"},{"label":"C8"},
                                          {"label":"C9"}
                                        ]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOM_FORMAT_INVALID_COLUMN_COUNT"));
    }

    /**
     * Given a blank format-level label, when POST /retro/formats is called, then it returns 400
     * with code {@code INVALID_FORMAT_LABEL}.
     */
    @Test
    void create_withBlankLabel_returns400() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"","columns":[{"label":"Un"},{"label":"Deux"}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT_LABEL"));
    }

    /**
     * Given a format-level label longer than 60 characters, when POST /retro/formats is called,
     * then it returns 400 with code {@code INVALID_FORMAT_LABEL}.
     */
    @Test
    void create_withLabelTooLong_returns400() throws Exception {
        String longLabel = "a".repeat(61);
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"%s","columns":[{"label":"Un"},{"label":"Deux"}]}
                                        """.formatted(longLabel)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FORMAT_LABEL"));
    }

    /**
     * Given a blank column label, when POST /retro/formats is called, then it returns 400 with
     * code {@code INVALID_COLUMN_LABEL}.
     */
    @Test
    void create_withBlankColumnLabel_returns400() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":""},{"label":"Deux"}]}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COLUMN_LABEL"));
    }

    /**
     * Given a column label longer than 40 characters, when POST /retro/formats is called, then
     * it returns 400 with code {@code INVALID_COLUMN_LABEL}.
     */
    @Test
    void create_withColumnLabelTooLong_returns400() throws Exception {
        String longLabel = "a".repeat(41);
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":"%s"},{"label":"Deux"}]}
                                        """.formatted(longLabel)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COLUMN_LABEL"));
    }

    /**
     * Given two columns whose labels slug to the same base key, when POST /retro/formats is
     * called, then the second occurrence's key is disambiguated with a numeric suffix — proven
     * end-to-end through the real DB unique constraint on {@code (format_id, column_key)}.
     */
    @Test
    void create_withCollidingColumnLabels_disambiguatesKeys() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":"Bien"},{"label":"Bien"}]}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columns[0].key").value("BIEN"))
                .andExpect(jsonPath("$.columns[1].key").value("BIEN_2"));
    }

    /**
     * Given no {@code Authorization} header, when POST /retro/formats is called, then it
     * returns 401.
     */
    @Test
    void create_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"Format","columns":[{"label":"Un"},{"label":"Deux"}]}
                                        """))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Structural immutability of system formats (US20.2.1 security AC)
    // -------------------------------------------------------------------------

    /**
     * Given a system format's key, when PUT /retro/formats/{key} is called, then there is no
     * route mapped at all (404) — proof that no request of any kind can alter a system format.
     */
    @Test
    void put_onSystemFormatKey_returns404NoRouteMapped() throws Exception {
        mockMvc.perform(
                        put(FORMATS_PATH + "/START_STOP_CONTINUE")
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a system format's key, when PATCH /retro/formats/{key} is called, then there is no
     * route mapped at all (404).
     */
    @Test
    void patch_onSystemFormatKey_returns404NoRouteMapped() throws Exception {
        mockMvc.perform(
                        patch(FORMATS_PATH + "/START_STOP_CONTINUE")
                                .header("Authorization", "Bearer " + memberToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a system format's key, when DELETE /retro/formats/{key} is called, then there is no
     * route mapped at all (404).
     */
    @Test
    void delete_onSystemFormatKey_returns404NoRouteMapped() throws Exception {
        mockMvc.perform(
                        delete(FORMATS_PATH + "/START_STOP_CONTINUE")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a custom format via the real API using the given caller's bearer token. */
    private void createCustomFormat(final String token, final String label, final String columnsJson)
            throws Exception {
        mockMvc.perform(
                        post(FORMATS_PATH)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"label":"%s","columns":%s}
                                        """.formatted(label, columnsJson)))
                .andExpect(status().isCreated());
    }
}
