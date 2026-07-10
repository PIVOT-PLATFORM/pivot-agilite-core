package fr.pivot.agilite.exception;

/**
 * Thrown when a retro action is created or updated with an {@code ownerUserId} that does not
 * resolve to a member of the session's team (US20.3.1).
 *
 * <p>Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} — unlike a not-found
 * team/session, this is a client input error, not an access-control boundary.
 */
public class RetroActionOwnerNotTeamMemberException extends RuntimeException {

    /**
     * Creates an exception for the given invalid owner reference.
     *
     * @param ownerUserId the {@code public.users.id} that is not a member of the session's team
     */
    public RetroActionOwnerNotTeamMemberException(final Long ownerUserId) {
        super("ownerUserId is not a member of this session's team: " + ownerUserId);
    }
}
