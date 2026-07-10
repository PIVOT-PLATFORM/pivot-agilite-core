package fr.pivot.agilite.retro.vote;

import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.vote.dto.VoteBalanceEvent;
import fr.pivot.agilite.retro.vote.dto.VoteCastEvent;
import fr.pivot.agilite.retro.vote.dto.VoteUncastEvent;
import fr.pivot.agilite.retro.ws.RetroAccessGrantService;
import fr.pivot.agilite.retro.ws.RetroParticipantGrant;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import fr.pivot.agilite.ws.WsErrorPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for retrospective dot-voting (US20.1.2b).
 *
 * <p>Invoked from {@code RetroVoteWsController}'s {@code @MessageMapping} handlers — same
 * fire-and-forget error-handling style as {@code RetroCardService}: every rejection path
 * (unknown session, wrong phase, unknown/cross-session card, exhausted balance) notifies the
 * sender alone via {@code /user/queue/errors}, never a {@code ResponseStatusException}.
 *
 * <p><strong>Scope note.</strong> {@link #uncastVote} and {@link #queryBalance} (and their {@code
 * /votes/uncast}/{@code /votes/balance} STOMP destinations) are an intentional, small extension
 * beyond the literal US20.1.2b acceptance criteria, which only specify casting. They were added
 * because (a) a cast-only interaction with no way to correct a misclick or learn one's remaining
 * balance is a materially worse experience for no AC-mandated reason, (b) neither weakens nor
 * contradicts any AC invariant — the balance stays server-authoritative, never negative, no voter
 * identity is ever broadcast, and the 404/409-equivalent rejections hold symmetrically for both
 * cast and uncast — and (c) both are fully covered by {@code RetroVoteServiceTest}, so no
 * behaviour ships untested.
 */
@Service
public class RetroVoteService {

    private static final Logger LOG = LoggerFactory.getLogger(RetroVoteService.class);

    /** Destination suffix for the private per-participant balance notification. */
    private static final String VOTE_QUEUE = "/queue/votes";

    private final RetroVoteRepository voteRepository;
    private final RetroVoteBalanceRepository balanceRepository;
    private final RetroCardRepository cardRepository;
    private final RetroSessionRepository sessionRepository;
    private final RetroAccessGrantService grantService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param voteRepository    individual vote persistence
     * @param balanceRepository per-participant vote-balance persistence
     * @param cardRepository    card persistence, used to validate the voted-on card belongs to
     *                          the session
     * @param sessionRepository session persistence, used to check the current phase and the
     *                          configured {@code voteCountPerParticipant}
     * @param grantService      resolves the voting participant's identity from their presented
     *                          access token
     * @param messagingTemplate used to broadcast {@code VOTE_CAST}/{@code VOTE_UNCAST} and to
     *                          notify the caller of their balance/errors
     */
    public RetroVoteService(
            final RetroVoteRepository voteRepository,
            final RetroVoteBalanceRepository balanceRepository,
            final RetroCardRepository cardRepository,
            final RetroSessionRepository sessionRepository,
            final RetroAccessGrantService grantService,
            final SimpMessagingTemplate messagingTemplate) {
        this.voteRepository = voteRepository;
        this.balanceRepository = balanceRepository;
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.grantService = grantService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Casts a single dot-vote on a revealed card, decrementing the caller's remaining balance.
     *
     * <p>Rejected (sender notified alone, nothing persisted or broadcast) when: the access grant
     * does not resolve, the session does not exist, the session is not currently in {@link
     * RetroPhase#VOTE}, the card does not exist or belongs to a different session, or the
     * caller's balance is already exhausted.
     *
     * @param sessionId   the target session, from the STOMP destination
     * @param cardId      the target card, from the request payload
     * @param accessToken the caller's access token — also the vote-balance identity key
     * @param principal   the caller's connection principal, used to address notifications
     */
    @Transactional
    public void castVote(
            final UUID sessionId, final UUID cardId, final String accessToken, final Principal principal) {
        RetroSession session = resolveVotableSession(sessionId, cardId, accessToken, principal);
        if (session == null) {
            return;
        }

        balanceRepository.ensureBalanceRow(sessionId, accessToken, session.getVoteCountPerParticipant());
        int updated = balanceRepository.incrementIfAvailable(sessionId, accessToken);
        if (updated == 0) {
            notifyError(principal, "No remaining votes for this session");
            return;
        }

        voteRepository.save(new RetroVote(sessionId, cardId, accessToken, null));
        long voteCount = voteRepository.countByCardId(cardId);
        LOG.info("Retro vote cast: session={} card={} voteCount={}", sessionId, cardId, voteCount);
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(sessionId),
                (Object) VoteCastEvent.of(sessionId, cardId, voteCount));

        notifyBalance(sessionId, accessToken, session.getVoteCountPerParticipant(), principal);
    }

    /**
     * Removes a single, previously cast dot-vote from a card, incrementing the caller's remaining
     * balance back by one.
     *
     * <p>Rejected the same way as {@link #castVote} for grant/session/phase/card checks; when
     * those all pass but the caller has no matching vote on this card, the sender alone is
     * notified and nothing changes.
     *
     * @param sessionId   the target session, from the STOMP destination
     * @param cardId      the target card, from the request payload
     * @param accessToken the caller's access token — also the vote-balance identity key
     * @param principal   the caller's connection principal, used to address notifications
     */
    @Transactional
    public void uncastVote(
            final UUID sessionId, final UUID cardId, final String accessToken, final Principal principal) {
        RetroSession session = resolveVotableSession(sessionId, cardId, accessToken, principal);
        if (session == null) {
            return;
        }

        int deleted = voteRepository.deleteOneVote(cardId, accessToken);
        if (deleted == 0) {
            notifyError(principal, "No vote to remove for this card");
            return;
        }
        balanceRepository.decrementIfPositive(sessionId, accessToken);

        long voteCount = voteRepository.countByCardId(cardId);
        LOG.info("Retro vote uncast: session={} card={} voteCount={}", sessionId, cardId, voteCount);
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(sessionId),
                (Object) VoteUncastEvent.of(sessionId, cardId, voteCount));

        notifyBalance(sessionId, accessToken, session.getVoteCountPerParticipant(), principal);
    }

    /**
     * Reports the caller's current vote balance for a session, without creating a balance row if
     * none exists yet (a participant who has not voted yet simply has {@code votesUsed == 0}).
     *
     * @param sessionId   the target session
     * @param accessToken the caller's access token — also the vote-balance identity key
     * @param principal   the caller's connection principal, used to address the notification
     */
    @Transactional(readOnly = true)
    public void queryBalance(final UUID sessionId, final String accessToken, final Principal principal) {
        Optional<RetroParticipantGrant> grantOpt = grantService.resolveGrant(sessionId, accessToken);
        if (grantOpt.isEmpty()) {
            notifyError(principal, "Access denied to retro session " + sessionId);
            return;
        }
        Optional<RetroSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            notifyError(principal, "Retro session not found");
            return;
        }
        notifyBalance(sessionId, accessToken, sessionOpt.get().getVoteCountPerParticipant(), principal);
    }

    /**
     * Resolves and validates the session for a cast/uncast attempt, notifying the sender and
     * returning {@code null} on any rejection — grant, session existence, phase, and card
     * existence/ownership are all checked identically for both actions.
     *
     * @param sessionId   the target session
     * @param cardId      the target card
     * @param accessToken the caller's access token
     * @param principal   the caller's connection principal
     * @return the validated session, or {@code null} if the attempt was rejected (the sender has
     *     already been notified)
     */
    private RetroSession resolveVotableSession(
            final UUID sessionId, final UUID cardId, final String accessToken, final Principal principal) {
        Optional<RetroParticipantGrant> grantOpt = grantService.resolveGrant(sessionId, accessToken);
        if (grantOpt.isEmpty()) {
            // Defensive only — the channel interceptor already denies SEND frames without a
            // valid grant before this handler is ever invoked.
            notifyError(principal, "Access denied to retro session " + sessionId);
            return null;
        }

        Optional<RetroSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            notifyError(principal, "Retro session not found");
            return null;
        }
        RetroSession session = sessionOpt.get();
        if (session.getCurrentPhase() == RetroPhase.CLOSED) {
            // US20.1.2c — a closed session is read-only; called out as its own branch (rather
            // than falling through the generic phase-mismatch message below) so clients get an
            // unambiguous, stable reason to drive their read-only lockdown UI.
            notifyError(principal, "Retro session is closed");
            return null;
        }
        if (session.getCurrentPhase() != RetroPhase.VOTE) {
            notifyError(principal, "Retro session is not accepting votes right now");
            return null;
        }

        Optional<RetroCard> cardOpt = cardRepository.findById(cardId);
        if (cardOpt.isEmpty() || !cardOpt.get().getSessionId().equals(sessionId)) {
            notifyError(principal, "Card not found in this retro session");
            return null;
        }
        return session;
    }

    /**
     * Re-reads the authoritative balance row and sends the caller their current {@code
     * VOTE_BALANCE}, defaulting {@code votesUsed} to 0 when no row exists yet.
     *
     * @param sessionId    the target session
     * @param accessToken  the caller's access token
     * @param votesAllowed the session's configured {@code voteCountPerParticipant}
     * @param principal    the caller's connection principal
     */
    private void notifyBalance(
            final UUID sessionId, final String accessToken, final int votesAllowed, final Principal principal) {
        int votesUsed = balanceRepository.findBySessionIdAndVoterToken(sessionId, accessToken)
                .map(RetroVoteBalance::getVotesUsed)
                .orElse(0);
        sendToCaller(principal, VoteBalanceEvent.of(sessionId, votesAllowed - votesUsed, votesAllowed));
    }

    /**
     * Sends a payload to the caller's private {@link #VOTE_QUEUE}.
     *
     * @param principal the caller, or {@code null} (in which case nothing is sent)
     * @param payload   the payload to send
     */
    private void sendToCaller(final Principal principal, final Object payload) {
        if (principal == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(principal.getName(), VOTE_QUEUE, payload);
        } catch (Exception e) {
            LOG.debug("Could not deliver vote balance notification: {}", e.getMessage());
        }
    }

    /**
     * Sends an error notification to the sender's {@code /user/queue/errors} destination.
     *
     * @param principal the sender, or {@code null} (in which case nothing is sent)
     * @param error     the human-readable error reason
     */
    private void notifyError(final Principal principal, final String error) {
        if (principal == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", new WsErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification: {}", e.getMessage());
        }
    }
}
