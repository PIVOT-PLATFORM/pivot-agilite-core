package fr.pivot.agilite.retro.action.dto;

import fr.pivot.agilite.retro.action.RetroAction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API response for a single retro action (US20.3.1).
 *
 * @param id              the action's unique identifier
 * @param sessionId       the session this action was created from
 * @param teamId          the owning team's {@code public.teams.id}
 * @param sourceCardId    the source card's id, or {@code null}
 * @param title           the action's title
 * @param ownerUserId     the assignee's {@code public.users.id}, or {@code null}
 * @param dueDate         the due date, or {@code null}
 * @param status          the current status name (one of {@code A_FAIRE}/{@code EN_COURS}/
 *                        {@code TERMINEE}/{@code ABANDONNEE})
 * @param createdByUserId the creating caller's {@code public.users.id}
 * @param createdAt       creation timestamp
 * @param updatedAt       last-update timestamp
 */
public record RetroActionResponse(
        UUID id,
        UUID sessionId,
        Long teamId,
        UUID sourceCardId,
        String title,
        Long ownerUserId,
        LocalDate dueDate,
        String status,
        Long createdByUserId,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Builds the response from a persisted entity.
     *
     * @param action the persisted action
     * @return the response record
     */
    public static RetroActionResponse from(final RetroAction action) {
        return new RetroActionResponse(
                action.getId(),
                action.getSessionId(),
                action.getTeamId(),
                action.getSourceCardId(),
                action.getTitle(),
                action.getOwnerUserId(),
                action.getDueDate(),
                action.getStatus().name(),
                action.getCreatedByUserId(),
                action.getCreatedAt(),
                action.getUpdatedAt());
    }
}
