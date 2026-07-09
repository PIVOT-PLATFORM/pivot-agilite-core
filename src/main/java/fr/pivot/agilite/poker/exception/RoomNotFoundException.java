package fr.pivot.agilite.poker.exception;

/**
 * Thrown when a planning poker room cannot be found for the caller's tenant (US09.1.1) — either
 * because no room exists with the given id, or because it belongs to a different tenant. Both
 * cases are deliberately indistinguishable (mapped to HTTP 404 by {@code GlobalExceptionHandler})
 * to avoid confirming cross-tenant existence — see the transversal tenant-isolation rule in this
 * repo's {@code CLAUDE.md}.
 */
public class RoomNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given room id.
     *
     * @param roomId the room id that could not be found for the caller's tenant
     */
    public RoomNotFoundException(final Long roomId) {
        super("Room not found: " + roomId);
    }
}
