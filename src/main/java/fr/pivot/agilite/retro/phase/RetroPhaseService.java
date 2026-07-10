package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.exception.RetroFacilitatorOnlyException;
import fr.pivot.agilite.exception.RetroInvalidPhaseTransitionException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.card.dto.RevealedCard;
import fr.pivot.agilite.retro.phase.dto.CardsRevealedEvent;
import fr.pivot.agilite.retro.phase.dto.PhaseChangedEvent;
import fr.pivot.agilite.retro.phase.dto.RevealResponse;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for retro session phase transitions and card reveal (US20.1.2a).
 *
 * <p>Three entry points:
 * <ul>
 *   <li>{@link #closeContribution} — facilitator-triggered, immediate {@code CONTRIBUTION} →
 *       {@code REVUE} transition, before any configured timer would have expired it.</li>
 *   <li>{@link #autoTransitionToRevue} — the same transition, triggered by {@code
 *       RetroPhaseScheduler} once a session's configured {@code contributionTimerSeconds} has
 *       elapsed since creation. No caller identity to check — this is a system action.</li>
 *   <li>{@link #reveal} — facilitator-triggered, broadcasts every submitted card in clear,
 *       grouped by column, without itself advancing the phase any further (US20.1.2b owns the
 *       {@code REVUE} → {@code VOTE} transition, triggered independently).</li>
 * </ul>
 */
@Service
public class RetroPhaseService {

    private static final Logger LOG = LoggerFactory.getLogger(RetroPhaseService.class);

    private final RetroSessionRepository sessionRepository;
    private final RetroCardRepository cardRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param sessionRepository retro session persistence
     * @param cardRepository    card persistence, used to assemble the reveal payload
     * @param messagingTemplate used to broadcast {@code PHASE_CHANGED}/{@code CARDS_REVEALED}
     * @param clock             the shared clock, overridable in tests
     */
    public RetroPhaseService(
            final RetroSessionRepository sessionRepository,
            final RetroCardRepository cardRepository,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.cardRepository = cardRepository;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Manually closes the contribution phase, immediately transitioning to {@link
     * RetroPhase#REVUE} — before any configured timer would have expired it.
     *
     * @param sessionId the session to transition
     * @param callerId  the authenticated caller's user id
     * @param tenantId  the authenticated caller's tenant id
     * @return the session's new phase
     * @throws RetroSessionNotFoundException           if the session does not exist, or belongs
     *                                                  to a different tenant
     * @throws RetroFacilitatorOnlyException           if the caller is not the facilitator
     * @throws RetroInvalidPhaseTransitionException    if the session is not currently in {@link
     *                                                  RetroPhase#CONTRIBUTION}
     */
    @Transactional
    public RetroPhase closeContribution(final UUID sessionId, final Long callerId, final Long tenantId) {
        RetroSession session = loadForTenant(sessionId, tenantId);
        requireFacilitator(session, callerId);
        requirePhase(session, RetroPhase.CONTRIBUTION);
        transitionTo(session, RetroPhase.REVUE);
        return RetroPhase.REVUE;
    }

    /**
     * System-triggered (timer-based) transition to {@link RetroPhase#REVUE} — a no-op if the
     * session is no longer in {@link RetroPhase#CONTRIBUTION} (e.g. already manually closed).
     *
     * @param sessionId the session to transition
     */
    @Transactional
    public void autoTransitionToRevue(final UUID sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.getCurrentPhase() == RetroPhase.CONTRIBUTION)
                .ifPresent(session -> transitionTo(session, RetroPhase.REVUE));
    }

    /**
     * Triggers the reveal: broadcasts every submitted card in clear, grouped by column, on the
     * session's regular (all-participants) topic. Does not itself change the session's phase.
     *
     * @param sessionId the session to reveal
     * @param callerId  the authenticated caller's user id
     * @param tenantId  the authenticated caller's tenant id
     * @return the revealed cards, grouped by column
     * @throws RetroSessionNotFoundException        if the session does not exist, or belongs to
     *                                               a different tenant
     * @throws RetroFacilitatorOnlyException        if the caller is not the facilitator
     * @throws RetroInvalidPhaseTransitionException if the session has not yet reached {@link
     *                                               RetroPhase#REVUE}
     */
    @Transactional
    public RevealResponse reveal(final UUID sessionId, final Long callerId, final Long tenantId) {
        RetroSession session = loadForTenant(sessionId, tenantId);
        requireFacilitator(session, callerId);
        requirePhase(session, RetroPhase.REVUE);

        List<RetroCard> cards = cardRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        Map<String, List<RevealedCard>> grouped = new LinkedHashMap<>();
        for (RetroCard card : cards) {
            grouped.computeIfAbsent(card.getColumnKey(), key -> new java.util.ArrayList<>())
                    .add(new RevealedCard(card.getId(), card.getContent()));
        }

        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(sessionId),
                (Object) CardsRevealedEvent.of(sessionId, grouped));
        LOG.info("Retro session revealed: session={} cardCount={}", sessionId, cards.size());
        return new RevealResponse(sessionId, cards.size(), grouped);
    }

    /**
     * Loads a session, scoped to the caller's tenant.
     *
     * @param sessionId the session id
     * @param tenantId  the caller's tenant id
     * @return the matching session
     * @throws RetroSessionNotFoundException if not found or owned by a different tenant
     */
    private RetroSession loadForTenant(final UUID sessionId, final Long tenantId) {
        RetroSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RetroSessionNotFoundException(sessionId));
        if (!session.getTenantId().equals(tenantId)) {
            throw new RetroSessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * Verifies the caller is the session's facilitator.
     *
     * @param session  the session
     * @param callerId the caller's user id
     * @throws RetroFacilitatorOnlyException if the caller is not the facilitator
     */
    private void requireFacilitator(final RetroSession session, final Long callerId) {
        if (!session.getFacilitatorUserId().equals(callerId)) {
            throw new RetroFacilitatorOnlyException(session.getId());
        }
    }

    /**
     * Verifies the session is currently in the required phase.
     *
     * @param session       the session
     * @param requiredPhase the phase required for the action being attempted
     * @throws RetroInvalidPhaseTransitionException if the session is in a different phase
     */
    private void requirePhase(final RetroSession session, final RetroPhase requiredPhase) {
        if (session.getCurrentPhase() != requiredPhase) {
            throw new RetroInvalidPhaseTransitionException(
                    session.getId(), requiredPhase, session.getCurrentPhase());
        }
    }

    /**
     * Persists a phase transition and broadcasts {@code PHASE_CHANGED}.
     *
     * @param session  the session to transition
     * @param newPhase the phase to transition to
     */
    private void transitionTo(final RetroSession session, final RetroPhase newPhase) {
        RetroPhase previous = session.getCurrentPhase();
        session.setCurrentPhase(newPhase);
        sessionRepository.save(session);
        LOG.info("Retro session phase changed: session={} {} -> {}", session.getId(), previous, newPhase);
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(session.getId()),
                (Object) PhaseChangedEvent.of(session.getId(), previous, newPhase, clock.instant()));
    }
}
