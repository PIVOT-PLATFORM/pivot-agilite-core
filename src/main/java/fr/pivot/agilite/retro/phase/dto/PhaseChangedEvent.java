package fr.pivot.agilite.retro.phase.dto;

import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.vote.dto.RankedCard;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code PHASE_CHANGED} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} whenever a session's phase advances (US20.1.2a).
 *
 * @param type          always {@code "PHASE_CHANGED"} — discriminator for the shared session
 *                      topic
 * @param sessionId     the session whose phase changed
 * @param previousPhase the phase the session was in immediately before this transition
 * @param currentPhase  the phase the session is now in
 * @param changedAt     when the transition happened
 * @param rankedCards   the cards ranked by vote count, {@code null} for every transition except
 *                      {@code VOTE} → {@code ACTION} (US20.1.2b) — kept as the last, nullable
 *                      component so every pre-existing call site built via {@link #of} is
 *                      unaffected
 */
public record PhaseChangedEvent(
        String type, UUID sessionId, RetroPhase previousPhase, RetroPhase currentPhase, Instant changedAt,
        List<RankedCard> rankedCards) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "PHASE_CHANGED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator and no ranking — every phase
     * transition except {@code VOTE} → {@code ACTION}.
     *
     * @param sessionId     the session whose phase changed
     * @param previousPhase the phase the session was in immediately before this transition
     * @param currentPhase  the phase the session is now in
     * @param changedAt     when the transition happened
     * @return the constructed event
     */
    public static PhaseChangedEvent of(
            final UUID sessionId, final RetroPhase previousPhase,
            final RetroPhase currentPhase, final Instant changedAt) {
        return new PhaseChangedEvent(TYPE, sessionId, previousPhase, currentPhase, changedAt, null);
    }

    /**
     * Builds the event with {@link #TYPE} as its discriminator, carrying the vote-count ranking
     * (US20.1.2b) — used exclusively for the {@code VOTE} → {@code ACTION} transition.
     *
     * @param sessionId     the session whose phase changed
     * @param previousPhase the phase the session was in immediately before this transition
     * @param currentPhase  the phase the session is now in
     * @param changedAt     when the transition happened
     * @param rankedCards   every card, ranked by vote count descending (ties broken by original
     *                      submission order)
     * @return the constructed event
     */
    public static PhaseChangedEvent ofWithRanking(
            final UUID sessionId, final RetroPhase previousPhase, final RetroPhase currentPhase,
            final Instant changedAt, final List<RankedCard> rankedCards) {
        return new PhaseChangedEvent(TYPE, sessionId, previousPhase, currentPhase, changedAt, rankedCards);
    }
}
