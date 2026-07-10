package fr.pivot.agilite.retro.phase.dto;

import fr.pivot.agilite.retro.session.RetroPhase;

import java.time.Instant;
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
 */
public record PhaseChangedEvent(
        String type, UUID sessionId, RetroPhase previousPhase, RetroPhase currentPhase, Instant changedAt) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "PHASE_CHANGED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
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
        return new PhaseChangedEvent(TYPE, sessionId, previousPhase, currentPhase, changedAt);
    }
}
