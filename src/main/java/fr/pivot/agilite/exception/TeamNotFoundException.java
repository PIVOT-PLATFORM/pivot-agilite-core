package fr.pivot.agilite.exception;

/**
 * Thrown when a team does not exist, belongs to another tenant, or the caller is not one of its
 * members (US14.1.1).
 *
 * <p>Using a single exception for all three cases prevents cross-tenant/cross-team information
 * disclosure (the caller cannot distinguish "team doesn't exist" from "team exists but you're
 * not a member").
 */
public class TeamNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given team identifier.
     *
     * @param teamId the {@code public.teams.id} that could not be found or accessed
     */
    public TeamNotFoundException(final Long teamId) {
        super("Team not found: " + teamId);
    }
}
