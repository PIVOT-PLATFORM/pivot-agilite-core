package fr.pivot.agilite.exception;

/**
 * Thrown when a retro session creation request has {@code format = "CUSTOM"} but no {@code
 * customFormatId} (US20.2.1).
 *
 * <p>Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} with machine-readable
 * {@code CUSTOM_FORMAT_ID_REQUIRED} code.
 */
public class RetroCustomFormatIdRequiredException extends RuntimeException {

    /** Creates the exception with a fixed, self-explanatory message. */
    public RetroCustomFormatIdRequiredException() {
        super("customFormatId is required when format is CUSTOM");
    }
}
