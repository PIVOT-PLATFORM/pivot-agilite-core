package fr.pivot.agilite.retro.phase.dto;

import fr.pivot.agilite.retro.session.RetroPhase;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code SESSION_CLOSED} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} when a session transitions to its terminal
 * {@link RetroPhase#CLOSED} phase (US20.1.2c) — facilitator-triggered ({@code
 * RetroPhaseService#closeSession}) or timer-based ({@code RetroPhaseService#autoTransitionToClose}
 * / {@code RetroPhaseScheduler#checkActionTimers}), always from {@link RetroPhase#ACTION} (this
 * US's AC only ever closes an in-progress ACTION phase).
 *
 * <p><strong>Deliberately a distinct event type from {@code PHASE_CHANGED}</strong> (used for
 * every other transition): {@code CLOSED} is a terminal state that every client — including the
 * upcoming US20.3.1 action-creation UI — must treat unambiguously as "read-only from now on",
 * rather than inferring that from a generic phase value buried in a {@code PHASE_CHANGED}
 * payload. This is the final event of a session's lifecycle; the session's REST detail endpoint
 * ({@code GET /retro/sessions/{id}}, always readable regardless of phase per US20.1.1) remains
 * the authoritative source for the full final state (timers, vote counts, etc.) for any client
 * that reconnects after this event was broadcast.
 *
 * @param type          always {@code "SESSION_CLOSED"} — discriminator for the shared session
 *                      topic
 * @param sessionId     the session that closed
 * @param previousPhase always {@link RetroPhase#ACTION} — kept explicit (rather than assumed) so
 *                      a client reading only this event still has the full transition shape,
 *                      symmetrically with {@code PhaseChangedEvent#previousPhase}
 * @param closedAt      when the closure happened
 */
public record SessionClosedEvent(String type, UUID sessionId, RetroPhase previousPhase, Instant closedAt) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "SESSION_CLOSED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId     the session that closed
     * @param previousPhase the phase the session was in immediately before closure (always
     *                      {@link RetroPhase#ACTION})
     * @param closedAt      when the closure happened
     * @return the constructed event
     */
    public static SessionClosedEvent of(
            final UUID sessionId, final RetroPhase previousPhase, final Instant closedAt) {
        return new SessionClosedEvent(TYPE, sessionId, previousPhase, closedAt);
    }
}
