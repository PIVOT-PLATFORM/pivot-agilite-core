package fr.pivot.agilite.retro.format.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A single column of a custom retrospective format creation request (US20.2.1).
 *
 * <p>{@code color}, {@code description}, {@code icon} are all optional: an absent {@code color}
 * is defaulted by position from {@code RetroFormatService}'s default palette; an absent {@code
 * description}/{@code icon} stays {@code null} in the response, never defaulted.
 *
 * @param label       column label, 1-40 characters
 * @param color       optional hex color code
 * @param description optional column description
 * @param icon        optional icon identifier
 */
public record CreateRetroFormatColumnRequest(
        @NotBlank(message = "INVALID_COLUMN_LABEL")
        @Size(min = 1, max = 40, message = "INVALID_COLUMN_LABEL")
        String label,

        String color,

        String description,

        String icon) {
}
