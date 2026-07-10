package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when a planning poker ticket cannot be found for a given room (US09.2.2) — either
 * because no ticket exists with the given id, or because it belongs to a different room than the
 * one addressed by the request path.
 *
 * <p>Mapped to HTTP 404 Not Found by {@code GlobalExceptionHandler}. Both causes are deliberately
 * collapsed into the same indistinguishable response — same anti-enumeration posture as {@link
 * RoomNotFoundException}/{@code InviteCodeNotFoundException} (US09.1.1/US09.1.2): a caller can
 * never learn whether a ticket id is unknown outright or simply belongs to another room.
 */
public class TicketNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given ticket id.
     *
     * @param ticketId the ticket id that could not be found for the given room
     */
    public TicketNotFoundException(final UUID ticketId) {
        super("Ticket not found: " + ticketId);
    }
}
