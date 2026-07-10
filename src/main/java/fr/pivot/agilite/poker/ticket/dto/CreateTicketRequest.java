package fr.pivot.agilite.poker.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/agilite/poker/rooms/{roomId}/tickets} (US09.2.1).
 *
 * <p>{@code title} validation messages double as the machine-readable {@code code} property
 * surfaced by {@code GlobalExceptionHandler#handleValidation} (same convention as {@code
 * CreateRoomRequest}/{@code CreateRetroSessionRequest}).
 *
 * @param title the ticket's display title — required, 1-200 characters
 */
public record CreateTicketRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(max = 200, message = "INVALID_TITLE")
        String title) {
}
