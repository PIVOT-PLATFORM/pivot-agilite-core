package fr.pivot.agilite.retro.action.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for creating a new retro action ({@code POST /retro/sessions/{id}/actions},
 * US20.3.1).
 *
 * <p>{@code ownerUserId} and {@code sourceCardId} are business-validated in {@code
 * RetroActionService} (team membership / same-session ownership respectively), not by Bean
 * Validation — both checks require a database lookup scoped to the session, unlike the
 * single-field constraints below.
 *
 * @param title        action title, 1–200 characters
 * @param ownerUserId  optional assignee ({@code public.users.id}); when present must be a member
 *                     of the session's team
 * @param dueDate      optional due date
 * @param sourceCardId optional retrospective card this action originates from; when present must
 *                     belong to the same session
 */
public record CreateRetroActionRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 200, message = "INVALID_TITLE")
        String title,

        Long ownerUserId,

        LocalDate dueDate,

        UUID sourceCardId) {
}
