package fr.pivot.agilite.exception;

/**
 * Thrown when the {@code format} field of a retro session creation request does not match any
 * value of the {@link fr.pivot.agilite.retro.session.RetroFormat} catalogue.
 *
 * <p>Deliberately validated in the service layer against the enum's known constants — not via
 * direct Jackson enum deserialization on the DTO — so that an unknown value produces this
 * dedicated exception (mapped to HTTP 400 Bad Request with machine-readable {@code
 * INVALID_FORMAT} code by {@link GlobalExceptionHandler}) instead of a generic Jackson
 * deserialization failure.
 */
public class InvalidRetroFormatException extends RuntimeException {

    /**
     * Creates the exception for the given unknown format value.
     *
     * @param format the raw, unrecognized format string supplied by the caller
     */
    public InvalidRetroFormatException(final String format) {
        super("Invalid retro format: " + format);
    }
}
