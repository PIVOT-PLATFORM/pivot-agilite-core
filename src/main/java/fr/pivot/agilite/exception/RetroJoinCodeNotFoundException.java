package fr.pivot.agilite.exception;

/**
 * Thrown when a {@code joinCode} supplied to the public join-resolution endpoint does not match
 * any existing retro session.
 *
 * <p>Mapped to HTTP 404 Not Found by {@link GlobalExceptionHandler}. The message deliberately
 * never echoes the raw code back into the response.
 */
public class RetroJoinCodeNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for an unknown join code.
     */
    public RetroJoinCodeNotFoundException() {
        super("Join code not found");
    }
}
