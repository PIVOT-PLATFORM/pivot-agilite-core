package fr.pivot.agilite.retro.ws;

import fr.pivot.agilite.exception.RetroSessionExpiredException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.dto.RetroParticipantAccessResponse;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Mints retrospective session access grants for the realtime STOMP channel (US20.1.2a).
 *
 * <p><strong>Deliberately unauthenticated-friendly</strong> — mirrors US20.1.1's {@code
 * findByJoinCode} design pillar (frictionless join, "participant sans compte inclus"): a caller
 * presenting no bearer token at all, an invalid/expired one, or one belonging to a different
 * tenant than the session's own, is still granted access — simply as an anonymous participant
 * (see {@link RetroParticipantGrant#anonymous()}), never rejected outright. Only the session's
 * own existence/expiry/closure gates whether a grant is issued at all.
 *
 * <p><strong>Facilitator resolution.</strong> A caller is recognised as the facilitator only when
 * their bearer token resolves to a principal whose {@code tenantId} matches the session's own
 * <em>and</em> whose {@code userId} equals the session's {@code facilitatorUserId} — both
 * resolved server-side from the validated token, never from any client-supplied claim.
 */
@Service
public class RetroSessionAccessService {

    /** Minimum grant TTL applied defensively if a session's remaining lifetime is razor-thin. */
    private static final Duration MIN_TTL = Duration.ofMinutes(1);

    private final RetroSessionRepository sessionRepository;
    private final RetroAccessGrantService grantService;
    private final AuthenticatedPrincipalResolver principalResolver;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param sessionRepository retro session persistence
     * @param grantService      the Redis-backed access grant store
     * @param principalResolver bearer token validator (best-effort here — never throws, an
     *                          unresolved token simply yields an anonymous grant)
     * @param clock             the shared clock, overridable in tests
     */
    public RetroSessionAccessService(
            final RetroSessionRepository sessionRepository,
            final RetroAccessGrantService grantService,
            final AuthenticatedPrincipalResolver principalResolver,
            final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.grantService = grantService;
        this.principalResolver = principalResolver;
        this.clock = clock;
    }

    /**
     * Joins a retro session's realtime channel, minting a fresh access grant.
     *
     * @param sessionId      the session to join
     * @param rawBearerToken the raw bearer token from the caller's {@code Authorization} header,
     *                       or {@code null}/blank if none was presented
     * @return the minted grant and the destinations the caller should use
     * @throws RetroSessionNotFoundException if no session exists with this id
     * @throws RetroSessionExpiredException  if the session is already closed or past its
     *                                       {@code expiresAt} — mirrors the join-code gate, this
     *                                       is a *new-join* gate, never applied to an already-
     *                                       granted participant reconnecting mid-way (out of
     *                                       scope here — grants simply expire on their own TTL)
     */
    @Transactional(readOnly = true)
    public RetroParticipantAccessResponse join(final UUID sessionId, final String rawBearerToken) {
        RetroSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RetroSessionNotFoundException(sessionId));

        if (session.getCurrentPhase() == RetroPhase.CLOSED
                || !clock.instant().isBefore(session.getExpiresAt())) {
            throw new RetroSessionExpiredException("Retro session is not joinable");
        }

        RetroParticipantGrant participant = resolveParticipant(session, rawBearerToken);
        String accessToken = UUID.randomUUID().toString();
        Duration ttl = ttlUntilExpiry(session);
        grantService.grantAccess(sessionId, accessToken, participant, ttl);

        return new RetroParticipantAccessResponse(
                accessToken,
                ttl.toSeconds(),
                participant.facilitator(),
                RetroSessionDestinations.roomTopic(sessionId),
                participant.facilitator() ? RetroSessionDestinations.facilitatorTopic(sessionId) : null,
                RetroSessionDestinations.APP_ROOM_PREFIX + sessionId + "/cards");
    }

    /**
     * Resolves the participant identity for a join attempt, downgrading to anonymous on any
     * failure to resolve a same-tenant identity.
     *
     * @param session        the session being joined
     * @param rawBearerToken the raw bearer token, or {@code null}/blank
     * @return the resolved participant grant
     */
    private RetroParticipantGrant resolveParticipant(final RetroSession session, final String rawBearerToken) {
        if (rawBearerToken == null || rawBearerToken.isBlank()) {
            return RetroParticipantGrant.anonymous();
        }
        return principalResolver.resolve(rawBearerToken)
                .filter(principal -> principal.tenantId().equals(session.getTenantId()))
                .map(principal -> toGrant(session, principal))
                .orElseGet(RetroParticipantGrant::anonymous);
    }

    /**
     * Builds a grant for a successfully resolved, same-tenant principal.
     *
     * @param session   the session being joined
     * @param principal the resolved, same-tenant principal
     * @return the corresponding grant, with {@code facilitator} set when the principal's user id
     *         matches the session's facilitator
     */
    private RetroParticipantGrant toGrant(final RetroSession session, final AuthenticatedPrincipal principal) {
        boolean facilitator = principal.userId().equals(session.getFacilitatorUserId());
        return new RetroParticipantGrant(principal.userId(), principal.tenantId(), facilitator);
    }

    /**
     * Computes the grant TTL so it never outlives the session's own {@code expiresAt}.
     *
     * @param session the session being joined
     * @return the remaining time until expiry, floored to {@link #MIN_TTL}
     */
    private Duration ttlUntilExpiry(final RetroSession session) {
        Duration remaining = Duration.between(clock.instant(), session.getExpiresAt());
        return remaining.compareTo(MIN_TTL) < 0 ? MIN_TTL : remaining;
    }
}
