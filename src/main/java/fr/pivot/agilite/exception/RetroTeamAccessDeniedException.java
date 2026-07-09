package fr.pivot.agilite.exception;

/**
 * Thrown when the caller is authenticated, and the target team genuinely exists in the caller's
 * own tenant, but the caller is not a member of it.
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link GlobalExceptionHandler} — safe to disclose that the
 * resource exists here since the tenant match is already established (unlike {@link
 * RetroTeamNotFoundException}, which covers the cross-tenant / non-existent cases).
 */
public class RetroTeamAccessDeniedException extends RuntimeException {

    /**
     * Creates an access-denied exception for the given team.
     *
     * @param teamId the {@code public.teams.id} the caller is not a member of
     */
    public RetroTeamAccessDeniedException(final Long teamId) {
        super("Caller is not a member of team: " + teamId);
    }
}
