package fr.pivot.agilite.retro.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and resolves retrospective session access grants — the sole mechanism by which a STOMP
 * client is authorized to subscribe to, or send into, a retro session's realtime channel
 * (US20.1.2a).
 *
 * <p><strong>Mirrors {@code fr.pivot.agilite.poker.ws.RoomAccessGrantService}</strong> (EN09.1) —
 * same Redis-backed, TTL-expiring, opaque-token grant model, adapted for one difference: a retro
 * grant also carries a resolved {@link RetroParticipantGrant} identity (userId/tenantId/
 * facilitator), not just a boolean "has access". This is required because, unlike planning poker
 * (fully anonymous by design), this US must (a) attribute non-anonymous cards to their real
 * author and (b) let the facilitator's connection see full card content pre-reveal — both need to
 * know *who* is asking, resolved once, server-side, at grant-mint time ({@code
 * RetroSessionAccessService}), never trusted from a STOMP frame afterward.
 *
 * <p><strong>Encoding.</strong> The grant value is a plain, pipe-delimited string —
 * {@code "<userId>|<tenantId>|<facilitator>"}, empty segments meaning {@code null} — deliberately
 * not JSON: this repo has no other Redis-JSON precedent, and the shape is fixed and trivial enough
 * that a small serialization format avoids pulling Jackson into a Redis value codec.
 *
 * <p><strong>Tenant isolation.</strong> The grant is keyed by {@code (sessionId, accessToken)}
 * only, exactly like the poker precedent — {@code accessToken} is an opaque, unguessable value
 * minted by {@code RetroSessionAccessService}. There is no method here that accepts a client-
 * supplied tenantId/userId to trust; the identity embedded in a grant was already resolved
 * server-side before this service ever sees it.
 */
@Service
public class RetroAccessGrantService {

    private static final Logger LOG = LoggerFactory.getLogger(RetroAccessGrantService.class);

    /** Redis key prefix for retro session access grants. */
    private static final String GRANT_KEY_PREFIX = "retro:session-access:";

    /** Delimiter between the three encoded fields of a grant value. */
    private static final String DELIMITER = "|";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the service with the shared Redis client.
     *
     * @param redisTemplate Redis client used to store and resolve grants
     */
    public RetroAccessGrantService(final StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Grants a participant access to a session for the given duration.
     *
     * <p>Idempotent: granting again for the same {@code (sessionId, accessToken)} simply
     * refreshes the TTL and identity from now.
     *
     * @param sessionId   the session's identifier
     * @param accessToken the opaque access token minted by the caller for this participant
     * @param participant the resolved participant identity to associate with this grant
     * @param ttl         how long the grant remains valid
     */
    public void grantAccess(
            final UUID sessionId, final String accessToken,
            final RetroParticipantGrant participant, final Duration ttl) {
        String key = grantKey(sessionId, accessToken);
        redisTemplate.opsForValue().set(key, encode(participant), ttl);
        LOG.info("Retro session access granted: session={} facilitator={} ttlSeconds={}",
                sessionId, participant.facilitator(), ttl.toSeconds());
    }

    /**
     * Resolves the participant identity for a currently valid grant, if one exists.
     *
     * @param sessionId   the session's identifier
     * @param accessToken the access token presented by the client, or {@code null}/blank if none
     * @return the resolved participant, or empty if no currently valid grant exists for this
     *         exact {@code (sessionId, accessToken)} pair
     */
    public Optional<RetroParticipantGrant> resolveGrant(final UUID sessionId, final String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(grantKey(sessionId, accessToken));
        return raw == null ? Optional.empty() : Optional.of(decode(raw));
    }

    /**
     * Checks whether a currently valid grant exists for the given session and access token.
     *
     * @param sessionId   the session's identifier
     * @param accessToken the access token presented by the client, or {@code null}/blank if none
     * @return {@code true} if a non-expired grant exists for this exact pair
     */
    public boolean hasAccess(final UUID sessionId, final String accessToken) {
        return resolveGrant(sessionId, accessToken).isPresent();
    }

    /**
     * Revokes a session access grant immediately, before its TTL would naturally expire it.
     *
     * @param sessionId   the session's identifier
     * @param accessToken the access token to revoke
     */
    public void revokeAccess(final UUID sessionId, final String accessToken) {
        redisTemplate.delete(grantKey(sessionId, accessToken));
        LOG.info("Retro session access revoked: session={}", sessionId);
    }

    /**
     * Builds the Redis key for a given session/access-token pair.
     *
     * @param sessionId   the session's identifier
     * @param accessToken the access token
     * @return the Redis key
     */
    private String grantKey(final UUID sessionId, final String accessToken) {
        return GRANT_KEY_PREFIX + sessionId + ":" + accessToken;
    }

    /**
     * Encodes a participant grant into its Redis string value.
     *
     * @param participant the participant to encode
     * @return the encoded {@code "<userId>|<tenantId>|<facilitator>"} string
     */
    private static String encode(final RetroParticipantGrant participant) {
        return nullToEmpty(participant.userId())
                + DELIMITER + nullToEmpty(participant.tenantId())
                + DELIMITER + (participant.facilitator() ? "1" : "0");
    }

    /**
     * Decodes a Redis string value back into a participant grant.
     *
     * @param raw the encoded value
     * @return the decoded participant grant
     */
    private static RetroParticipantGrant decode(final String raw) {
        String[] parts = raw.split(java.util.regex.Pattern.quote(DELIMITER), -1);
        Long userId = parts[0].isEmpty() ? null : Long.valueOf(parts[0]);
        Long tenantId = parts[1].isEmpty() ? null : Long.valueOf(parts[1]);
        boolean facilitator = "1".equals(parts[2]);
        return new RetroParticipantGrant(userId, tenantId, facilitator);
    }

    /**
     * Returns the empty string for a {@code null} value, or the value's string form otherwise.
     *
     * @param value the nullable value
     * @return the empty string or {@code value.toString()}
     */
    private static String nullToEmpty(final Object value) {
        return value == null ? "" : value.toString();
    }
}
