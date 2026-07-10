package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when a facilitator attempts to create a new ticket in a room that already has one
 * currently open ({@code VOTING}) (US09.2.1).
 *
 * <p>Mapped to HTTP 409 Conflict by {@code GlobalExceptionHandler} with machine-readable code
 * {@code ACTIVE_TICKET_EXISTS}. Backed by a partial unique database index on {@code
 * agilite.poker_tickets(room_id) WHERE status = 'VOTING'} — this exception is the application-
 * level guard raised before ever reaching that constraint, not a workaround for its absence.
 */
public class ActiveTicketExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for the given room.
     *
     * @param roomId the room that already has an open ticket
     */
    public ActiveTicketExistsException(final UUID roomId) {
        super("Room already has an active ticket: " + roomId);
    }
}
