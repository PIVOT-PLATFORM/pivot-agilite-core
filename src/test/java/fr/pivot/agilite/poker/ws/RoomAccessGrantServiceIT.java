package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.poker.exception.PokerFacilitatorOnlyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test proving {@link RoomAccessGrantService} against a real Redis instance —
 * grant/check/revoke round trip and TTL-driven expiry.
 *
 * <p>Instantiates the service directly against a real {@link StringRedisTemplate} rather than
 * loading the full Spring context: this class has exactly one collaborator and no Spring-managed
 * state worth bootstrapping a context for.
 */
@Testcontainers
class RoomAccessGrantServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RoomAccessGrantService grantService;

    /** Connects a real {@link StringRedisTemplate} to the Testcontainers Redis instance. */
    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        grantService = new RoomAccessGrantService(redisTemplate);
    }

    /** Releases the Redis connection after each test. */
    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /**
     * Given no grant has ever been issued for a room/token pair, when access is checked, then
     * it is denied.
     */
    @Test
    void hasAccessIsFalseWithoutAnyGrant() {
        assertThat(grantService.hasAccess(UUID.randomUUID(), "never-granted")).isFalse();
    }

    /**
     * Given a grant issued for a room/token pair, when access is checked for that exact pair,
     * then it is allowed.
     */
    @Test
    void hasAccessIsTrueAfterGrant() {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(grantService.hasAccess(roomId, "token-1")).isTrue();
    }

    /**
     * Security AC (cross-room isolation): given a grant issued for one room, when access is
     * checked for a different room using the same token, then it is denied — a grant only ever
     * authorizes the exact room it was issued for.
     */
    @Test
    void grantForOneRoomDoesNotAuthorizeAnotherRoom() {
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(grantService.hasAccess(otherRoomId, "token-1")).isFalse();
    }

    /**
     * Given a grant with a very short TTL, when the TTL elapses, then the grant no longer
     * authorizes access — proving expiry is real, not just accepted as a parameter.
     */
    @Test
    void grantExpiresAfterItsTtl() throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "token-1", Duration.ofSeconds(1));
        assertThat(grantService.hasAccess(roomId, "token-1")).isTrue();

        Thread.sleep(1500);

        assertThat(grantService.hasAccess(roomId, "token-1")).isFalse();
    }

    /**
     * Given a currently valid grant, when it is explicitly revoked, then access is denied
     * immediately, before its TTL would naturally have expired it.
     */
    @Test
    void revokeAccessDeniesImmediately() {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "token-1", Duration.ofMinutes(5));
        assertThat(grantService.hasAccess(roomId, "token-1")).isTrue();

        grantService.revokeAccess(roomId, "token-1");

        assertThat(grantService.hasAccess(roomId, "token-1")).isFalse();
    }

    /**
     * Given a {@code null} or blank access token, when access is checked, then it is denied
     * without querying Redis for a nonsensical key.
     */
    @Test
    void hasAccessRejectsNullOrBlankToken() {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(grantService.hasAccess(roomId, null)).isFalse();
        assertThat(grantService.hasAccess(roomId, "")).isFalse();
        assertThat(grantService.hasAccess(roomId, "   ")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Guest (anonymous) grants — US09.3.1
    // -------------------------------------------------------------------------

    /**
     * Given a guest grant issued via {@link RoomAccessGrantService#grantGuestAccess}, when
     * generic room access is checked ({@link RoomAccessGrantService#hasAccess}), then it is
     * allowed — exactly like a standard grant (an anonymous guest can subscribe/vote just like
     * an authenticated participant, US09.3.1 AC).
     */
    @Test
    void guestGrantAuthorizesGenericRoomAccess() {
        UUID roomId = UUID.randomUUID();
        grantService.grantGuestAccess(roomId, "guest-token", Duration.ofMinutes(5));

        assertThat(grantService.hasAccess(roomId, "guest-token")).isTrue();
    }

    /**
     * Given a guest grant, when {@link RoomAccessGrantService#isGuest} is checked, then it
     * reports {@code true} — the mechanism by which a future facilitator-only action
     * distinguishes an anonymous participant from an authenticated one.
     */
    @Test
    void isGuestIsTrueForGuestGrant() {
        UUID roomId = UUID.randomUUID();
        grantService.grantGuestAccess(roomId, "guest-token", Duration.ofMinutes(5));

        assertThat(grantService.isGuest(roomId, "guest-token")).isTrue();
    }

    /**
     * Given a standard (authenticated participant) grant issued via {@link
     * RoomAccessGrantService#grantAccess}, when {@link RoomAccessGrantService#isGuest} is
     * checked, then it reports {@code false} — never confused with a guest grant.
     */
    @Test
    void isGuestIsFalseForStandardGrant() {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "participant-token", Duration.ofMinutes(5));

        assertThat(grantService.isGuest(roomId, "participant-token")).isFalse();
    }

    /**
     * Given no grant at all for a room/token pair, when {@link
     * RoomAccessGrantService#isGuest} is checked, then it reports {@code false} — absence of a
     * grant is never mistaken for a guest grant.
     */
    @Test
    void isGuestIsFalseWithoutAnyGrant() {
        assertThat(grantService.isGuest(UUID.randomUUID(), "never-granted")).isFalse();
    }

    /**
     * Security AC (US09.3.1): given a guest grant, when {@link
     * RoomAccessGrantService#requireNonGuest} is called for that exact pair, then it throws
     * {@link PokerFacilitatorOnlyException} — the primitive facilitator-only actions
     * (US09.2.1/US09.2.2, once built) must call to reject an anonymous guest — proving the
     * mechanism works, not a silent success.
     */
    @Test
    void requireNonGuestThrowsForGuestGrant() {
        UUID roomId = UUID.randomUUID();
        grantService.grantGuestAccess(roomId, "guest-token", Duration.ofMinutes(5));

        assertThatThrownBy(() -> grantService.requireNonGuest(roomId, "guest-token"))
                .isInstanceOf(PokerFacilitatorOnlyException.class);
    }

    /**
     * Given a standard (authenticated participant) grant, when {@link
     * RoomAccessGrantService#requireNonGuest} is called for that exact pair, then it does not
     * throw — a non-guest grant is never wrongly rejected.
     */
    @Test
    void requireNonGuestDoesNotThrowForStandardGrant() {
        UUID roomId = UUID.randomUUID();
        grantService.grantAccess(roomId, "participant-token", Duration.ofMinutes(5));

        assertThatCode(() -> grantService.requireNonGuest(roomId, "participant-token"))
                .doesNotThrowAnyException();
    }

    /**
     * Given a guest grant with a very short TTL, when the TTL elapses, then generic room access
     * is denied — the same TTL-driven expiry as a standard grant (2h-inactivity cap, ADR-026
     * §2, realized as a Redis TTL exactly like {@link #grantExpiresAfterItsTtl}).
     */
    @Test
    void guestGrantExpiresAfterItsTtl() throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        grantService.grantGuestAccess(roomId, "guest-token", Duration.ofSeconds(1));
        assertThat(grantService.hasAccess(roomId, "guest-token")).isTrue();

        Thread.sleep(1500);

        assertThat(grantService.hasAccess(roomId, "guest-token")).isFalse();
    }
}
