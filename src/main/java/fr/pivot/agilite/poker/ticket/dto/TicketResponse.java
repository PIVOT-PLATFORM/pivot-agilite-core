package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * API response shape for a planning poker ticket (US09.2.1), returned by both {@code POST
 * /api/agilite/poker/rooms/{roomId}/tickets} and {@code GET .../tickets/current}.
 *
 * @param id        ticket primary key
 * @param roomId    the owning room's identifier
 * @param title     the ticket's display title
 * @param status    the ticket's current lifecycle status ({@code "VOTING"} or {@code "REVEALED"})
 * @param createdAt creation timestamp
 */
public record TicketResponse(UUID id, UUID roomId, String title, String status, Instant createdAt) {
}
