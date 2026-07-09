package fr.pivot.agilite.poker.ws;

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
}
