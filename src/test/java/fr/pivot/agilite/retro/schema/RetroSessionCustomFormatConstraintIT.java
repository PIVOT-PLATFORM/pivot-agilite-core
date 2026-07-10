package fr.pivot.agilite.retro.schema;

import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the {@code format}/{@code customFormatId} invariant on {@code
 * agilite.retro_sessions} (US20.2.1 Gate-1 AC) is enforced by the database schema itself — the
 * {@code chk_retro_sessions_custom_format_id} {@code CHECK} constraint — not merely by {@code
 * RetroSessionService}'s cross-field validation, mirroring the same structural-guarantee
 * approach already established by {@link RetroCardsAnonymityConstraintIT} (US20.1.1).
 *
 * <p>Every insert here is plain JDBC, deliberately bypassing the service layer, so a future
 * direct write (admin tool, bulk fix, bug in a later US) is proven to still be rejected by the
 * database, not just by application code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class RetroSessionCustomFormatConstraintIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties and seeds the {@code public} schema
     * before the Spring context and its Flyway run (which creates {@code agilite.retro_sessions})
     * start.
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

    /**
     * Given {@code format = 'CUSTOM'} and a {@code null} {@code custom_format_id}, when a raw
     * SQL insert is attempted, then it is rejected by the {@code
     * chk_retro_sessions_custom_format_id} CHECK constraint.
     */
    @Test
    void insertCustomFormatWithNullCustomFormatId_violatesCheckConstraint() throws Exception {
        Fixture fixture = seedTenantTeamUser();

        assertThatThrownBy(() -> insertSession(fixture, "CUSTOM", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_retro_sessions_custom_format_id");
    }

    /**
     * Given a non-{@code CUSTOM} format and a non-null {@code custom_format_id}, when a raw SQL
     * insert is attempted, then it is rejected by the same CHECK constraint.
     */
    @Test
    void insertNonCustomFormatWithCustomFormatId_violatesCheckConstraint() throws Exception {
        Fixture fixture = seedTenantTeamUser();
        UUID customFormatId = seedCustomFormat(fixture);

        assertThatThrownBy(() -> insertSession(fixture, "START_STOP_CONTINUE", customFormatId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_retro_sessions_custom_format_id");
    }

    /**
     * Given {@code format = 'CUSTOM'} and a non-null {@code custom_format_id}, when inserted,
     * then it succeeds — the constraint permits the one valid CUSTOM shape.
     */
    @Test
    void insertCustomFormatWithCustomFormatId_succeeds() throws Exception {
        Fixture fixture = seedTenantTeamUser();
        UUID customFormatId = seedCustomFormat(fixture);

        assertThatCode(() -> insertSession(fixture, "CUSTOM", customFormatId)).doesNotThrowAnyException();
    }

    /**
     * Given a non-{@code CUSTOM} format and a {@code null} {@code custom_format_id}, when
     * inserted, then it succeeds — the constraint never blocks the normal, non-custom case.
     */
    @Test
    void insertNonCustomFormatWithNullCustomFormatId_succeeds() throws Exception {
        Fixture fixture = seedTenantTeamUser();

        assertThatCode(() -> insertSession(fixture, "START_STOP_CONTINUE", null)).doesNotThrowAnyException();
    }

    /** Seeds a tenant, a team in it, and a member — the minimum needed as valid FK targets. */
    private Fixture seedTenantTeamUser() throws SQLException {
        AuthFixture user = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                user.tenantId(), "Constraint Team " + UUID.randomUUID());
        return new Fixture(user.tenantId(), teamId, user.userId());
    }

    /** Inserts a minimal custom format row directly, returning its id for use as a FK target. */
    private UUID seedCustomFormat(final Fixture fixture) throws SQLException {
        String sql = "INSERT INTO agilite.retro_formats (tenant_id, label, created_by_user_id) "
                + "VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fixture.tenantId());
            ps.setString(2, "Constraint Test Format");
            ps.setLong(3, fixture.userId());
            try (var rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /**
     * Attempts a raw JDBC insert into {@code agilite.retro_sessions} with the given format/
     * customFormatId shape.
     *
     * @param fixture        the seeded tenant/team/user to use as valid FK targets
     * @param format         the {@code format} value to insert
     * @param customFormatId the {@code custom_format_id} value to insert, or {@code null}
     * @throws SQLException if the insert is rejected (e.g. by the CHECK constraint)
     */
    private void insertSession(final Fixture fixture, final String format, final UUID customFormatId)
            throws SQLException {
        String sql = "INSERT INTO agilite.retro_sessions "
                + "(tenant_id, team_id, title, format, custom_format_id, facilitator_user_id, "
                + "join_code, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fixture.tenantId());
            ps.setLong(2, fixture.teamId());
            ps.setString(3, "Constraint Test Session");
            ps.setString(4, format);
            if (customFormatId != null) {
                ps.setObject(5, customFormatId);
            } else {
                ps.setNull(5, Types.OTHER);
            }
            ps.setLong(6, fixture.userId());
            ps.setString(7, UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT));
            ps.setObject(8, OffsetDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** Minimal seeded fixture: a tenant, a team in it, and a user. */
    private record Fixture(long tenantId, long teamId, long userId) {
    }
}
