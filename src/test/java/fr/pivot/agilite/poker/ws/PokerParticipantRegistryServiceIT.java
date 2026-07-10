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
 * Integration test proving {@link PokerParticipantRegistryService} against a real Redis instance
 * (US09.2.1) — registration, roster count, cross-room isolation, and TTL-driven expiry.
 *
 * <p>Instantiates the service directly against a real {@link StringRedisTemplate}, same
 * convention as {@code RoomAccessGrantServiceIT} — a single collaborator, no Spring context
 * needed.
 */
@Testcontainers
class PokerParticipantRegistryServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private PokerParticipantRegistryService registryService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        registryService = new PokerParticipantRegistryService(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    /**
     * Given no participant has ever registered for a room, when its roster is counted, then it
     * is zero.
     */
    @Test
    void countActiveIsZeroWithoutAnyRegistration() {
        assertThat(registryService.countActive(UUID.randomUUID())).isZero();
    }

    /**
     * Given one participant registered, when the roster is counted, then it is one.
     */
    @Test
    void countActiveIsOneAfterSingleRegistration() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(registryService.countActive(roomId)).isEqualTo(1L);
    }

    /**
     * Given two distinct access tokens registered for the same room, when the roster is
     * counted, then it reflects both distinct participants.
     */
    @Test
    void countActiveReflectsDistinctParticipants() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", Duration.ofMinutes(5));
        registryService.register(roomId, "token-2", Duration.ofMinutes(5));

        assertThat(registryService.countActive(roomId)).isEqualTo(2L);
    }

    /**
     * Given the same access token registered twice (idempotent re-join), when the roster is
     * counted, then it is still one — a Redis set never double-counts the same member.
     */
    @Test
    void registeringSameTokenTwiceDoesNotDoubleCount() {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", Duration.ofMinutes(5));
        registryService.register(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(registryService.countActive(roomId)).isEqualTo(1L);
    }

    /**
     * Security AC (cross-room isolation): given participants registered for one room, when a
     * different room's roster is counted, then it is unaffected.
     */
    @Test
    void registrationForOneRoomDoesNotAffectAnotherRoom() {
        UUID roomId = UUID.randomUUID();
        UUID otherRoomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", Duration.ofMinutes(5));

        assertThat(registryService.countActive(otherRoomId)).isZero();
    }

    /**
     * Given a roster registered with a very short TTL, when the TTL elapses without any further
     * registration, then the roster empties out — proving expiry is real, not just accepted as a
     * parameter.
     */
    @Test
    void rosterExpiresAfterItsTtl() throws InterruptedException {
        UUID roomId = UUID.randomUUID();
        registryService.register(roomId, "token-1", Duration.ofSeconds(1));
        assertThat(registryService.countActive(roomId)).isEqualTo(1L);

        Thread.sleep(1500);

        assertThat(registryService.countActive(roomId)).isZero();
    }
}
