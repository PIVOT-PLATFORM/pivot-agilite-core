package fr.pivot.agilite.poker.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Tracks the roster of participants who have ever obtained a room access grant (facilitator at
 * room creation, participants at join — US09.2.1), backing the live "X/Y have voted" counter's
 * denominator ({@code Y}).
 *
 * <p><strong>Deliberately not a WebSocket-connection presence tracker.</strong> {@code Y} counts
 * distinct access tokens registered for the room, not currently-open STOMP sessions — a
 * participant who closes their tab without returning stays counted until their access grant
 * itself expires (aligned with the room's own expiry, US09.1.1). Real-time disconnect detection
 * would require wiring into {@link fr.pivot.agilite.ws.WsSessionRegistry}'s connect/disconnect
 * lifecycle, deliberately deferred (see the US09.2.1 backlog file, section "Hors périmètre") —
 * this is the smallest mechanism that makes the counter meaningful without that added complexity.
 *
 * <p>Backed by a single Redis {@code Set} per room (member = raw access token — the same value
 * already treated as an ephemeral secret by {@link RoomAccessGrantService}, no new exposure). The
 * set's TTL is refreshed to the caller-supplied value on every {@link #register}, so it survives
 * as long as at least one participant has (re)joined within that window; it is never read back
 * as a per-member expiry (Redis sets do not support that), only as a whole-key TTL.
 */
@Service
public class PokerParticipantRegistryService {

    private static final Logger LOG = LoggerFactory.getLogger(PokerParticipantRegistryService.class);

    /** Redis key prefix for a room's participant roster set. */
    private static final String ROSTER_KEY_PREFIX = "poker:room-participants:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store the roster
     */
    public PokerParticipantRegistryService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Registers a participant (facilitator or joiner) into a room's roster, refreshing the
     * roster's overall TTL to {@code ttl} from now.
     *
     * <p>Idempotent: registering the same {@code accessToken} again simply refreshes the TTL —
     * the roster's size ({@link #countActive}) does not double-count it (Redis set semantics).
     *
     * @param roomId      the room's identifier
     * @param accessToken the participant's room-scoped access token
     * @param ttl         how long the roster entry remains valid
     */
    public void register(final UUID roomId, final String accessToken, final Duration ttl) {
        String key = rosterKey(roomId);
        redisTemplate.opsForSet().add(key, accessToken);
        redisTemplate.expire(key, ttl);
        LOG.info("Room participant registered: room={} ttlSeconds={}", roomId, ttl.toSeconds());
    }

    /**
     * Counts the distinct participants currently registered for a room.
     *
     * @param roomId the room's identifier
     * @return the roster size, or {@code 0} if the room has no registered participant (or its
     *     roster has fully expired)
     */
    public long countActive(final UUID roomId) {
        Long size = redisTemplate.opsForSet().size(rosterKey(roomId));
        return size != null ? size : 0L;
    }

    /**
     * Builds the Redis key for a given room's roster.
     *
     * @param roomId the room's identifier
     * @return the Redis key
     */
    private String rosterKey(final UUID roomId) {
        return ROSTER_KEY_PREFIX + roomId;
    }
}
