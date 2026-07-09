package fr.pivot.agilite.exception;

/**
 * Thrown when a {@code teamId} supplied on retro session creation does not exist, or exists but
 * belongs to a different tenant than the caller.
 *
 * <p>Using a single exception for both cases prevents cross-tenant information disclosure (the
 * caller cannot distinguish "team doesn't exist" from "team exists but belongs to someone
 * else") — mapped to HTTP 404 Not Found by {@link GlobalExceptionHandler}, never 403.
 */
public class RetroTeamNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given team identifier.
     *
     * @param teamId the {@code public.teams.id} that could not be found or accessed
     */
    public RetroTeamNotFoundException(final Long teamId) {
        super("Team not found: " + teamId);
    }
}
