package fr.pivot.agilite.exception;

/**
 * Thrown when a retro session creation request supplies a non-{@code null} {@code
 * customFormatId} although {@code format} is not {@code "CUSTOM"} (US20.2.1) — rejected
 * explicitly rather than silently ignored, per this US's acceptance criteria.
 *
 * <p>Mapped to HTTP 400 Bad Request by {@link GlobalExceptionHandler} with machine-readable
 * {@code CUSTOM_FORMAT_ID_NOT_ALLOWED} code.
 */
public class RetroCustomFormatIdNotAllowedException extends RuntimeException {

    /** Creates the exception with a fixed, self-explanatory message. */
    public RetroCustomFormatIdNotAllowedException() {
        super("customFormatId is only allowed when format is CUSTOM");
    }
}
