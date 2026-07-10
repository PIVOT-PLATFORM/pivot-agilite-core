package fr.pivot.agilite.retro.card;

import fr.pivot.agilite.retro.card.dto.CardAddedFacilitatorEvent;
import fr.pivot.agilite.retro.card.dto.CardAddedMaskedEvent;
import fr.pivot.agilite.retro.card.dto.SubmitCardRequest;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
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
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for retrospective card submission (US20.1.2a).
 *
 * <p>Invoked from {@code RetroCardWsController}'s {@code @MessageMapping} handler — every
 * rejection path here (unknown session, wrong phase, invalid content) notifies the sender alone
 * via {@code /user/queue/errors}, never a {@code ResponseStatusException}: there is no HTTP
 * response to shape for a STOMP SEND, and broadcasting a rejection to the whole session would
 * make no sense.
 */
@Service
public class RetroCardService {

    private static final Logger LOG = LoggerFactory.getLogger(RetroCardService.class);

    /** Maximum accepted card content length — defensive UX limit, not a database constraint. */
    private static final int MAX_CONTENT_LENGTH = 2000;

    /** Maximum accepted column key length — matches the database column width. */
    private static final int MAX_COLUMN_KEY_LENGTH = 50;

    private final RetroCardRepository cardRepository;
    private final RetroSessionRepository sessionRepository;
    private final RetroAccessGrantService grantService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param cardRepository    card persistence
     * @param sessionRepository session persistence, used to check the current phase
     * @param grantService      resolves the submitting participant's identity from their
     *                          presented access token
     * @param messagingTemplate used to broadcast {@code CARD_ADDED} events and error notifications
     * @param clock             the shared clock, overridable in tests
     */
    public RetroCardService(
            final RetroCardRepository cardRepository,
            final RetroSessionRepository sessionRepository,
            final RetroAccessGrantService grantService,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.cardRepository = cardRepository;
        this.sessionRepository = sessionRepository;
        this.grantService = grantService;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Submits a new contribution card, persists it, and broadcasts the masked {@code CARD_ADDED}
     * event to every participant plus the unmasked one to the facilitator-only preview topic.
     *
     * @param sessionId   the target session, from the STOMP destination
     * @param request     the submitted content/column/anonymous flag
     * @param accessToken the caller's access token, re-resolved here (already validated once by
     *                    {@code RetroChannelInterceptor} before this handler ever runs)
     * @param principal   the caller's connection principal, used to address error notifications
     */
    @Transactional
    public void submit(
            final UUID sessionId, final SubmitCardRequest request,
            final String accessToken, final Principal principal) {
        Optional<RetroParticipantGrant> grantOpt = grantService.resolveGrant(sessionId, accessToken);
        if (grantOpt.isEmpty()) {
            // Defensive only — the channel interceptor already denies SEND frames without a
            // valid grant before this handler is ever invoked.
            notifyError(principal, "Access denied to retro session " + sessionId);
            return;
        }

        Optional<RetroSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            notifyError(principal, "Retro session not found");
            return;
        }
        RetroSession session = sessionOpt.get();
        if (session.getCurrentPhase() != RetroPhase.CONTRIBUTION) {
            notifyError(principal, "Retro session is not accepting contributions right now");
            return;
        }

        String content = validateContent(request.content(), principal);
        if (content == null) {
            return;
        }
        String columnKey = validateColumnKey(request.columnKey(), principal);
        if (columnKey == null) {
            return;
        }

        RetroParticipantGrant grant = grantOpt.get();
        boolean anonymous = request.anonymous() || grant.userId() == null;
        Long authorUserId = anonymous ? null : grant.userId();

        RetroCard card = new RetroCard(sessionId, columnKey, content, anonymous, authorUserId, clock.instant());
        RetroCard saved = cardRepository.save(card);
        LOG.info("Retro card submitted: session={} column={} anonymous={}", sessionId, columnKey, anonymous);

        long cardCount = cardRepository.countBySessionIdAndColumnKey(sessionId, columnKey);
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(sessionId),
                (Object) CardAddedMaskedEvent.of(sessionId, columnKey, cardCount));
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.facilitatorTopic(sessionId),
                (Object) CardAddedFacilitatorEvent.of(sessionId, saved.getId(), columnKey, content, anonymous));
    }

    /**
     * Validates card content, notifying the sender and returning {@code null} if invalid.
     *
     * @param content   the raw content to validate
     * @param principal the sender, for error addressing
     * @return the trimmed, valid content, or {@code null} if rejected
     */
    private String validateContent(final String content, final Principal principal) {
        if (content == null || content.isBlank()) {
            notifyError(principal, "Card content must not be blank");
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            notifyError(principal, "Card content exceeds " + MAX_CONTENT_LENGTH + " characters");
            return null;
        }
        return trimmed;
    }

    /**
     * Validates the target column key, notifying the sender and returning {@code null} if
     * invalid. Deliberately does not validate against a per-format catalogue (US20.2.1's scope).
     *
     * @param columnKey the raw column key to validate
     * @param principal the sender, for error addressing
     * @return the trimmed, valid column key, or {@code null} if rejected
     */
    private String validateColumnKey(final String columnKey, final Principal principal) {
        if (columnKey == null || columnKey.isBlank()) {
            notifyError(principal, "Column key must not be blank");
            return null;
        }
        String trimmed = columnKey.trim();
        if (trimmed.length() > MAX_COLUMN_KEY_LENGTH) {
            notifyError(principal, "Column key exceeds " + MAX_COLUMN_KEY_LENGTH + " characters");
            return null;
        }
        return trimmed;
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
