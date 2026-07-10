package fr.pivot.agilite.retro.schema;

import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.session.dto.CreateRetroSessionRequest;
import fr.pivot.agilite.retro.session.RetroSessionService;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the anonymity guarantee on {@code agilite.retro_cards} (US20.1.1 Gate-1 AC) is
 * enforced by the database schema itself — the {@code chk_retro_cards_anonymous_no_author}
 * {@code CHECK} constraint — not merely documented in a comment.
 *
 * <p>No {@code RetroCard} JPA entity exists in this US (business logic for cards is US20.1.2a's
 * scope) — every insert here is plain JDBC against the real Testcontainers-provisioned
 * PostgreSQL database, exactly as the Gate-1 AC calls for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
class RetroCardsAnonymityConstraintIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /**
     * Supplies container-derived connection properties and seeds the {@code public} schema
     * before the Spring context and its Flyway run (which creates {@code agilite.retro_cards})
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

    @Autowired
    private RetroSessionService sessionService;

    @Autowired
    private RetroSessionRepository sessionRepository;

    /**
     * Seeds a tenant/team/user and a real retro session, returning the session id and the
     * facilitator's user id — both needed as valid foreign key targets for the card inserts
     * below.
     */
    private UUID[] seedSessionAndAuthor() throws SQLException {
        AuthFixture author = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                author.tenantId(), "Anonymity Team");
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, author.userId());

        var response = sessionService.create(
                new CreateRetroSessionRequest(
                        "Anonymity Test Retro", "START_STOP_CONTINUE", teamId,
                        null, null, null, null, null, null),
                author.userId(), author.tenantId());

        return new UUID[] {response.id()};
    }

    /**
     * Given an anonymous card ({@code is_anonymous = TRUE}) with a non-null
     * {@code author_user_id}, when a raw SQL insert is attempted, then it is rejected by the
     * {@code chk_retro_cards_anonymous_no_author} CHECK constraint — proof the anonymity
     * guarantee is enforced by the database itself, not just documented.
     */
    @Test
    void insertAnonymousCardWithAuthor_violatesCheckConstraint() throws Exception {
        UUID sessionId = seedSessionAndAuthor()[0];
        AuthFixture someUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        assertThatThrownBy(() -> insertCard(sessionId, true, someUser.userId()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_retro_cards_anonymous_no_author");
    }

    /**
     * Given an anonymous card with a {@code null} author, when inserted,
     * then it succeeds — the constraint permits the one valid anonymous shape.
     */
    @Test
    void insertAnonymousCardWithoutAuthor_succeeds() throws Exception {
        UUID sessionId = seedSessionAndAuthor()[0];

        assertThatCode(() -> insertCard(sessionId, true, null)).doesNotThrowAnyException();
    }

    /**
     * Given a non-anonymous card with a non-null author, when inserted,
     * then it succeeds — the constraint never blocks the normal, attributed case.
     */
    @Test
    void insertAttributedCard_succeeds() throws Exception {
        UUID sessionId = seedSessionAndAuthor()[0];
        AuthFixture someUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        assertThatCode(() -> insertCard(sessionId, false, someUser.userId())).doesNotThrowAnyException();
    }

    /**
     * Attempts a raw JDBC insert into {@code agilite.retro_cards} with the given anonymity
     * shape.
     *
     * @param sessionId    the owning session id (must already exist)
     * @param isAnonymous  the {@code is_anonymous} value to insert
     * @param authorUserId the {@code author_user_id} value to insert, or {@code null}
     * @throws SQLException if the insert is rejected (e.g. by the CHECK constraint)
     */
    private void insertCard(final UUID sessionId, final boolean isAnonymous, final Long authorUserId)
            throws SQLException {
        String sql = "INSERT INTO agilite.retro_cards "
                + "(session_id, column_key, content, is_anonymous, author_user_id) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, sessionId);
            ps.setString(2, "went-well");
            ps.setString(3, "Test content");
            ps.setBoolean(4, isAnonymous);
            if (authorUserId != null) {
                ps.setLong(5, authorUserId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            ps.executeUpdate();
        }
    }
}
