package fr.pivot.agilite.exception;

/**
 * Thrown for business-rule validation failures on a wheel's entries that require a database
 * lookup and therefore can't be expressed as simple bean validation annotations (US14.1.1):
 * duplicate entries ({@code DUPLICATE_ENTRY}), a {@code teamMemberId} that doesn't belong to the
 * wheel's team ({@code INVALID_ENTRY_TEAM_MEMBER}), or a malformed conditional field
 * ({@code INVALID_ENTRY}).
 */
public class WheelValidationException extends RuntimeException {

    private final String code;

    /**
     * Creates a validation exception carrying a machine-readable code.
     *
     * @param code    the machine-readable error code (e.g. {@code "DUPLICATE_ENTRY"})
     * @param message a human-readable detail message
     */
    public WheelValidationException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }
}
