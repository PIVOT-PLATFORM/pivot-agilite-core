package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a retro session does not exist, or exists but belongs to a different tenant than
 * the caller.
 *
 * <p>Using a single exception for both cases prevents cross-tenant information disclosure —
 * mapped to HTTP 404 Not Found by {@link GlobalExceptionHandler}.
 */
public class RetroSessionNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given session identifier.
     *
     * @param sessionId the UUID of the session that could not be found or accessed
     */
    public RetroSessionNotFoundException(final UUID sessionId) {
        super("Retro session not found: " + sessionId);
    }
}
