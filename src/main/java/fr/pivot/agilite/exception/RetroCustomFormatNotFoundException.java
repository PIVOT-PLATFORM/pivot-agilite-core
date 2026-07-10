package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a {@code customFormatId} supplied on retro session creation (with {@code format =
 * "CUSTOM"}) does not exist, or exists but belongs to a different tenant than the caller
 * (US20.2.1).
 *
 * <p>Using a single exception for both cases prevents cross-tenant information disclosure (the
 * caller cannot distinguish "format doesn't exist" from "format exists but belongs to someone
 * else") — mapped to HTTP 404 Not Found by {@link GlobalExceptionHandler}, never 403.
 */
public class RetroCustomFormatNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given custom format identifier.
     *
     * @param customFormatId the {@code agilite.retro_formats.id} that could not be found or
     *                       accessed
     */
    public RetroCustomFormatNotFoundException(final UUID customFormatId) {
        super("Custom format not found: " + customFormatId);
    }
}
