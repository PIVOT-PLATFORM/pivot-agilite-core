package fr.pivot.agilite.retro.ws;

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving {@link RetroAccessGrantService} against a real Redis instance — grant/
 * resolve/revoke round trip, participant identity round-trip encoding, and TTL-driven expiry
 * (US20.1.2a, mirrors {@code RoomAccessGrantServiceIT}, EN09.1).
 */
@Testcontainers
class RetroAccessGrantServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private RetroAccessGrantService grantService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        grantService = new RetroAccessGrantService(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void resolveGrantIsEmptyWithoutAnyGrant() {
        assertThat(grantService.resolveGrant(UUID.randomUUID(), "never-granted")).isEmpty();
        assertThat(grantService.hasAccess(UUID.randomUUID(), "never-granted")).isFalse();
    }

    /**
     * Given a facilitator grant, when resolved, then the exact identity round-trips through the
     * Redis encoding (userId, tenantId, facilitator flag).
     */
    @Test
    void resolveGrantRoundTripsFacilitatorIdentity() {
        UUID sessionId = UUID.randomUUID();
        RetroParticipantGrant participant = new RetroParticipantGrant(42L, 7L, true);
        grantService.grantAccess(sessionId, "token-1", participant, Duration.ofMinutes(5));

        Optional<RetroParticipantGrant> resolved = grantService.resolveGrant(sessionId, "token-1");

        assertThat(resolved).contains(participant);
    }

    /**
     * Given an anonymous grant (no userId/tenantId), when resolved, then both remain {@code null}
     * and {@code facilitator} is {@code false}.
     */
    @Test
    void resolveGrantRoundTripsAnonymousIdentity() {
        UUID sessionId = UUID.randomUUID();
        grantService.grantAccess(sessionId, "token-2", RetroParticipantGrant.anonymous(), Duration.ofMinutes(5));

        Optional<RetroParticipantGrant> resolved = grantService.resolveGrant(sessionId, "token-2");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().userId()).isNull();
        assertThat(resolved.get().tenantId()).isNull();
        assertThat(resolved.get().facilitator()).isFalse();
    }

    /** Cross-session isolation: a grant issued for one session never authorizes another. */
    @Test
    void grantForOneSessionDoesNotAuthorizeAnotherSession() {
        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        grantService.grantAccess(sessionId, "token-1", new RetroParticipantGrant(1L, 2L, false), Duration.ofMinutes(5));

        assertThat(grantService.hasAccess(otherSessionId, "token-1")).isFalse();
    }

    @Test
    void grantExpiresAfterItsTtl() throws InterruptedException {
        UUID sessionId = UUID.randomUUID();
        grantService.grantAccess(sessionId, "token-1", new RetroParticipantGrant(1L, 2L, false), Duration.ofSeconds(1));
        assertThat(grantService.hasAccess(sessionId, "token-1")).isTrue();

        Thread.sleep(1500);

        assertThat(grantService.hasAccess(sessionId, "token-1")).isFalse();
    }

    @Test
    void revokeAccessDeniesImmediately() {
        UUID sessionId = UUID.randomUUID();
        grantService.grantAccess(sessionId, "token-1", new RetroParticipantGrant(1L, 2L, false), Duration.ofMinutes(5));
        assertThat(grantService.hasAccess(sessionId, "token-1")).isTrue();

        grantService.revokeAccess(sessionId, "token-1");

        assertThat(grantService.hasAccess(sessionId, "token-1")).isFalse();
    }

    @Test
    void resolveGrantRejectsNullOrBlankToken() {
        UUID sessionId = UUID.randomUUID();
        grantService.grantAccess(sessionId, "token-1", new RetroParticipantGrant(1L, 2L, false), Duration.ofMinutes(5));

        assertThat(grantService.resolveGrant(sessionId, null)).isEmpty();
        assertThat(grantService.resolveGrant(sessionId, "")).isEmpty();
        assertThat(grantService.resolveGrant(sessionId, "   ")).isEmpty();
    }
}
