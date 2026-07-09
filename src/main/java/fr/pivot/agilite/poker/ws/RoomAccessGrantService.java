package fr.pivot.agilite.poker.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Issues and checks room access grants — the sole mechanism by which a STOMP client is
 * authorized to subscribe to, or send into, a planning-poker room (EN09.1).
 *
 * <p><strong>Why not a DB-backed membership check (unlike the whiteboard's
 * {@code MembershipCacheService} precedent, EN08.1):</strong> at the time this Enabler is built,
 * no {@code Room} entity exists yet — room CRUD (US09.1.1) and join-by-code (US09.1.2) are
 * separate, parallel backlog items with no dependency on this one (see
 * {@code pivot-docs/docs/backlog/sprints/sprint-8.md}, Vague 1 vs Vague 2). Building this
 * Enabler against a not-yet-existent JPA entity would either invent a fictional schema this
 * Enabler doesn't own, or force a merge-order dependency the sprint plan deliberately avoids.
 * Instead, this service defines the <em>contract</em> the join flow (US09.1.2) will call once
 * it validates an invite code: {@link #grantAccess} mints a room-scoped grant; this Enabler's
 * {@link PokerChannelInterceptor} is the sole consumer of {@link #hasAccess}. No caller of
 * {@link #grantAccess} exists yet — exactly like EN07.3's STOMP relay, which shipped with
 * "nothing publishes real data... yet" as a documented, accepted state for a foundational
 * Enabler (see {@code WebSocketConfig}'s class JavaDoc); this class is exercised directly by
 * its own tests in the meantime.
 *
 * <p><strong>Tenant isolation by construction:</strong> the grant is keyed by
 * {@code (roomId, accessToken)} only. {@code accessToken} is an opaque, unguessable value
 * (recommended: a random UUID or equivalent) minted by the join flow — never a client-supplied
 * tenantId/userId. There is no method here that accepts a tenantId from a caller acting on
 * behalf of an unauthenticated client, so there is no parameter for a malicious client to
 * spoof. Tenant separation is enforced upstream, by whichever authenticated context calls
 * {@link #grantAccess} (US09.1.2, once it resolves the room and validates the caller's tenant) —
 * this service simply never has an opportunity to trust a client's word for it.
 *
 * <p>Grants expire automatically via the Redis key TTL passed to {@link #grantAccess} — callers
 * are expected to align this with the room's own configured expiration (ADR-026: 24h default),
 * so no explicit revocation API is needed for the room-isolation guarantee itself.
 */
@Service
public class RoomAccessGrantService {

    private static final Logger LOG = LoggerFactory.getLogger(RoomAccessGrantService.class);

    /** Redis key prefix for room access grants. */
    private static final String GRANT_KEY_PREFIX = "poker:room-access:";

    /** Value stored for a granted key — presence of the key is what matters, not its content. */
    private static final String GRANTED_VALUE = "1";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store and check grants
     */
    public RoomAccessGrantService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Grants a participant access to a room for the given duration.
     *
     * <p>Idempotent: granting again for the same {@code (roomId, accessToken)} simply refreshes
     * the TTL to {@code ttl} from now.
     *
     * @param roomId      the room's identifier
     * @param accessToken the opaque access token minted by the caller for this participant
     * @param ttl         how long the grant remains valid
     */
    public void grantAccess(final UUID roomId, final String accessToken, final Duration ttl) {
        String key = grantKey(roomId, accessToken);
        redisTemplate.opsForValue().set(key, GRANTED_VALUE, ttl);
        LOG.info("Room access granted: room={} ttlSeconds={}", roomId, ttl.toSeconds());
    }

    /**
     * Checks whether a currently valid grant exists for the given room and access token.
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token presented by the client, or {@code null}/blank if
     *                    none was presented
     * @return {@code true} if a non-expired grant exists for this exact {@code (roomId,
     *         accessToken)} pair
     */
    public boolean hasAccess(final UUID roomId, final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        String key = grantKey(roomId, accessToken);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Revokes a room access grant immediately, before its TTL would naturally expire it.
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token to revoke
     */
    public void revokeAccess(final UUID roomId, final String accessToken) {
        redisTemplate.delete(grantKey(roomId, accessToken));
        LOG.info("Room access revoked: room={}", roomId);
    }

    /**
     * Builds the Redis key for a given room/access-token pair.
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token
     * @return the Redis key
     */
    private String grantKey(final UUID roomId, final String accessToken) {
        return GRANT_KEY_PREFIX + roomId + ":" + accessToken;
    }
}
