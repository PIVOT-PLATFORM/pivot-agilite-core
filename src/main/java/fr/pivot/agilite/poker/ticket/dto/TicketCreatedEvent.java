package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code TICKET_CREATED} event broadcast to every participant on {@code
 * /topic/agilite/poker/{roomId}} (US09.2.1) — fired once, right after a facilitator creates a
 * new ticket.
 *
 * <p>Shape figured for US09.2.2 (reveal, next wave), which broadcasts its own event on the same
 * topic without modifying this one.
 *
 * @param type      always {@code "TICKET_CREATED"} — discriminator for the shared room topic,
 *                  which also carries {@code VOTE_CAST}
 * @param roomId    the room this ticket belongs to
 * @param ticketId  the newly created ticket's id
 * @param title     the ticket's display title
 * @param createdAt creation timestamp
 */
public record TicketCreatedEvent(String type, UUID roomId, UUID ticketId, String title, Instant createdAt) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "TICKET_CREATED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId    the room this ticket belongs to
     * @param ticketId  the newly created ticket's id
     * @param title     the ticket's display title
     * @param createdAt creation timestamp
     * @return the constructed event
     */
    public static TicketCreatedEvent of(
            final UUID roomId, final UUID ticketId, final String title, final Instant createdAt) {
        return new TicketCreatedEvent(TYPE, roomId, ticketId, title, createdAt);
    }
}
