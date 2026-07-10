package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for {@code POST /api/agilite/poker/rooms/{roomId}/tickets/{ticketId}/reveal}
 * (US09.2.2), returned with HTTP 200 — a revelation transitions an existing resource, it does not
 * create one, unlike ticket creation's {@code TicketResponse}/201.
 *
 * <p>Carries the exact same {@code values}/{@code consensus} content as the {@code
 * VOTES_REVEALED} event broadcast to every other room subscriber at the same instant — the
 * facilitator (REST caller) and every other participant (WebSocket subscriber) receive strictly
 * identical information.
 *
 * @param id        ticket primary key
 * @param roomId    the owning room's identifier
 * @param title     the ticket's display title
 * @param status    always {@code "REVEALED"}
 * @param createdAt the ticket's original creation timestamp
 * @param revealedAt the revelation timestamp, freshly set by this call
 * @param values    every cast vote's raw value (including {@code "?"}) — anonymous, no
 *                  participant identity attached, no meaningful order
 * @param consensus the computed mean/median/majority
 */
public record RevealResponse(
        UUID id,
        UUID roomId,
        String title,
        String status,
        Instant createdAt,
        Instant revealedAt,
        List<String> values,
        ConsensusResponse consensus) {
}
