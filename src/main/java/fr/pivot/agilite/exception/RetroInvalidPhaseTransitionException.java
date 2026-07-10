package fr.pivot.agilite.exception;

import fr.pivot.agilite.retro.session.RetroPhase;

import java.util.UUID;

/**
 * Thrown when a retro session phase-dependent action is attempted while the session is not in
 * the phase that action requires (US20.1.2a) — e.g. closing contribution on a session already
 * past {@link RetroPhase#CONTRIBUTION}, or triggering reveal before the session has reached
 * {@link RetroPhase#REVUE}.
 *
 * <p>Mapped to HTTP 409 Conflict by {@link GlobalExceptionHandler}.
 */
public class RetroInvalidPhaseTransitionException extends RuntimeException {

    /**
     * Creates an invalid-phase-transition exception.
     *
     * @param sessionId     the session on which the action was attempted
     * @param requiredPhase the phase the action requires
     * @param actualPhase   the session's actual current phase
     */
    public RetroInvalidPhaseTransitionException(
            final UUID sessionId, final RetroPhase requiredPhase, final RetroPhase actualPhase) {
        super("Retro session " + sessionId + " must be in phase " + requiredPhase
                + " for this action, but is in phase " + actualPhase);
    }
}
