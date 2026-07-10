package fr.pivot.agilite.exception;

/**
 * Thrown when a raw {@code status} value (either the {@code PATCH /retro/actions/{actionId}}
 * request body, or the optional {@code status} filter on {@code GET
 * /retro/teams/{teamId}/actions}) does not match any {@link
 * fr.pivot.agilite.retro.action.RetroActionStatus} constant (US20.3.1).
 *
 * <p>Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} with a machine-readable
 * {@code INVALID_ACTION_STATUS} code — same pattern as {@code InvalidRetroFormatException}.
 */
public class InvalidRetroActionStatusException extends RuntimeException {

    /**
     * Creates an invalid-status exception for the given raw value.
     *
     * @param rawStatus the raw status string that did not match any known constant
     */
    public InvalidRetroActionStatusException(final String rawStatus) {
        super("Invalid retro action status: " + rawStatus);
    }
}
