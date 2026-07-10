package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a retro action does not exist, belongs to a different tenant than the caller, or
 * belongs to a team the caller is not a member of (US20.3.1).
 *
 * <p>Using a single exception for all three cases prevents cross-tenant/cross-team information
 * disclosure — mapped to HTTP 404 Not Found by {@link GlobalExceptionHandler}, same
 * anti-enumeration convention as {@link RetroSessionNotFoundException} and {@link
 * TeamNotFoundException}.
 */
public class RetroActionNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given action identifier.
     *
     * @param actionId the UUID of the action that could not be found or accessed
     */
    public RetroActionNotFoundException(final UUID actionId) {
        super("Retro action not found: " + actionId);
    }
}
