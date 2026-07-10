package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when an authenticated, same-tenant caller attempts a facilitator-only retro session
 * action (manual phase close, reveal trigger) but is not that session's facilitator (US20.1.2a).
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler} — safe to disclose the action
 * is restricted here since tenant match is already established (unlike {@link
 * RetroSessionNotFoundException}, which covers the cross-tenant / non-existent cases).
 */
public class RetroFacilitatorOnlyException extends RuntimeException {

    /**
     * Creates a facilitator-only exception for the given session.
     *
     * @param sessionId the session whose facilitator-only action was attempted
     */
    public RetroFacilitatorOnlyException(final UUID sessionId) {
        super("Caller is not the facilitator of retro session: " + sessionId);
    }
}
