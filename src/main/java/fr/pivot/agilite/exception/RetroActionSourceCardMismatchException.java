package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a retro action is created with a {@code sourceCardId} that does not exist, or
 * exists but belongs to a different session than the one the action is being created in
 * (US20.3.1).
 *
 * <p>Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} — a client input error, not
 * a 404: the caller already has a valid session in hand, and the invalid part is the card
 * reference within it.
 */
public class RetroActionSourceCardMismatchException extends RuntimeException {

    /**
     * Creates an exception for the given invalid source card reference.
     *
     * @param sourceCardId the card id that does not belong to the target session
     */
    public RetroActionSourceCardMismatchException(final UUID sourceCardId) {
        super("sourceCardId does not belong to this session: " + sourceCardId);
    }
}
