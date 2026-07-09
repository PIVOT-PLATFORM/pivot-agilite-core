package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a wheel does not exist, belongs to another tenant, or the caller is not a member
 * of its team (US14.1.1).
 *
 * <p>Using a single exception for all three cases prevents cross-tenant/cross-team information
 * disclosure (the caller cannot distinguish "wheel doesn't exist" from "wheel exists but you
 * can't see it").
 */
public class WheelNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given wheel identifier.
     *
     * @param wheelId the UUID of the wheel that could not be found or accessed
     */
    public WheelNotFoundException(final UUID wheelId) {
        super("Wheel not found: " + wheelId);
    }
}
