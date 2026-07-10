package fr.pivot.agilite.poker.ws;

import fr.pivot.agilite.poker.exception.PokerFacilitatorOnlyException;
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
 *
 * <p><strong>Guest (anonymous) grants — US09.3.1:</strong> {@link #grantGuestAccess} issues the
 * exact same kind of grant as {@link #grantAccess} — same key shape, same TTL semantics, checked
 * by the exact same {@link #hasAccess} used for SUBSCRIBE/SEND authorization (EN09.1) — the only
 * difference is the Redis value stored, which {@link #isGuest} reads back. This is deliberate:
 * an anonymous guest must be exactly as authorized as an authenticated participant for generic
 * room access (voting, receiving broadcasts, US09.2.1) — the two paths diverge only for
 * facilitator-only actions, via {@link #requireNonGuest}.
 */
@Service
public class RoomAccessGrantService {

    private static final Logger LOG = LoggerFactory.getLogger(RoomAccessGrantService.class);

    /** Redis key prefix for room access grants. */
    private static final String GRANT_KEY_PREFIX = "poker:room-access:";

    /** Value stored for a granted key — presence of the key is what matters, not its content. */
    private static final String GRANTED_VALUE = "1";

    /**
     * Value stored for a grant issued to an anonymous guest (US09.3.1) — distinguishes it from
     * {@link #GRANTED_VALUE} for {@link #isGuest}, while remaining an ordinary present key for
     * {@link #hasAccess} (generic room access never distinguishes the two).
     */
    private static final String GUEST_VALUE = "guest";

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
     * Grants an anonymous guest access to a room for the given duration (US09.3.1).
     *
     * <p>Authorizes exactly the same generic room access as {@link #grantAccess} (checked by the
     * same {@link #hasAccess}) — the only difference is that {@link #isGuest} subsequently
     * reports {@code true} for this exact {@code (roomId, accessToken)} pair, so a
     * facilitator-only action can reject it via {@link #requireNonGuest}. Idempotent, same as
     * {@link #grantAccess}.
     *
     * @param roomId      the room's identifier
     * @param accessToken the opaque access token minted for this anonymous guest
     * @param ttl         how long the grant remains valid — capped at 2h from issuance by the
     *                    caller (ADR-026 §2 inactivity window), never enforced here
     */
    public void grantGuestAccess(final UUID roomId, final String accessToken, final Duration ttl) {
        String key = grantKey(roomId, accessToken);
        redisTemplate.opsForValue().set(key, GUEST_VALUE, ttl);
        LOG.info("Guest room access granted: room={} ttlSeconds={}", roomId, ttl.toSeconds());
    }

    /**
     * Checks whether the currently valid grant for this room/access-token pair was issued to an
     * anonymous guest (US09.3.1) rather than an authenticated participant.
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token presented by the client, or {@code null}/blank if none
     * @return {@code true} if a non-expired guest grant exists for this exact pair; {@code false}
     *         if there is no grant at all, or the grant is a standard (non-guest) one
     */
    public boolean isGuest(final UUID roomId, final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        String value = redisTemplate.opsForValue().get(grantKey(roomId, accessToken));
        return GUEST_VALUE.equals(value);
    }

    /**
     * Rejects an anonymous guest attempting a facilitator-only action (US09.3.1) — the primitive
     * that sibling US09.2.1 (ticket creation) / US09.2.2 (reveal) must call once their own
     * facilitator-only actions exist (see this class's own JavaDoc precedent: {@link
     * #grantAccess}/{@link #hasAccess} defined this same contract before US09.1.2, their first
     * real caller, existed).
     *
     * @param roomId      the room's identifier
     * @param accessToken the access token presented by the caller
     * @throws PokerFacilitatorOnlyException if this exact pair currently holds a guest grant
     */
    public void requireNonGuest(final UUID roomId, final String accessToken) {
        if (isGuest(roomId, accessToken)) {
            throw new PokerFacilitatorOnlyException(roomId);
        }
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
