package fr.pivot.agilite.exception;

/**
 * Thrown by the public join-resolution endpoint ({@code GET /retro/sessions/join/{joinCode}})
 * when the target session's {@code expiresAt} has passed, or its {@code currentPhase} is
 * already {@code CLOSED}.
 *
 * <p>Maps to HTTP 410 Gone — the session existed (and, for an authenticated member, still does
 * for read purposes via {@code GET /retro/sessions/{id}}) but is no longer joinable by a new
 * participant. This gate applies <strong>only</strong> to join-code resolution — never to the
 * authenticated detail endpoint, which stays 200 for a closed/expired session (US20.1.2c:
 * read-only access to a closed session is a deliberate, separate requirement).
 */
public class RetroSessionExpiredException extends RuntimeException {

    /**
     * Creates the exception with a descriptive reason.
     *
     * @param reason a brief description (expired vs. closed)
     */
    public RetroSessionExpiredException(final String reason) {
        super(reason);
    }
}
