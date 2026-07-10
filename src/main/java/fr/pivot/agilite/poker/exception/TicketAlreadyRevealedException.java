package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when the facilitator attempts to reveal a planning poker ticket that has already been
 * revealed (US09.2.2) — revelation is a one-time, non-idempotent transition ({@code VOTING} →
 * {@code REVEALED}).
 *
 * <p>Mapped to HTTP 409 Conflict by {@code GlobalExceptionHandler} with machine-readable code
 * {@code TICKET_ALREADY_REVEALED}. Distinct from {@link ActiveTicketExistsException} (US09.2.1,
 * which guards ticket *creation* against a second concurrently open ticket) — this one guards the
 * *reveal* action against being repeated on an already-revealed ticket.
 */
public class TicketAlreadyRevealedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given ticket id.
     *
     * @param ticketId the ticket id that has already been revealed
     */
    public TicketAlreadyRevealedException(final UUID ticketId) {
        super("Ticket already revealed: " + ticketId);
    }
}
