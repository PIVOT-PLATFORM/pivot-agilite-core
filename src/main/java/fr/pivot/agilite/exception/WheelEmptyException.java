package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a {@code spin} is attempted on a wheel with zero entries (US14.2.1).
 *
 * <p>Purely a defensive guard: {@code POST}/{@code PUT /wheels/{id}} (US14.1.1) already reject an
 * empty {@code entries} list with {@code EMPTY_ENTRIES}, so a wheel should never legitimately
 * reach this state through the API. Kept to avoid a division-by-zero on the sum of weights should
 * that invariant ever be violated (e.g. future feature, data migration).
 */
public class WheelEmptyException extends RuntimeException {

    /**
     * Creates an empty-wheel exception for the given wheel identifier.
     *
     * @param wheelId the UUID of the wheel that has no entries
     */
    public WheelEmptyException(final UUID wheelId) {
        super("Wheel has no entries to draw from: " + wheelId);
    }
}
