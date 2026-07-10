package fr.pivot.agilite.retro.format.dto;

/**
 * A single column of a retrospective format (system or custom) — column-level shape shared by
 * {@code GET /retro/formats} and {@code POST /retro/formats} responses (US20.2.1).
 *
 * @param key         stable identifier of the column, unique within its owning format (uppercase
 *                    for system formats, a generated slug for custom ones)
 * @param label       human-readable column label
 * @param color       hex color code used to render the column
 * @param description optional column description, {@code null} if none
 * @param icon        optional icon identifier, {@code null} if none
 */
public record RetroFormatColumnResponse(
        String key,
        String label,
        String color,
        String description,
        String icon) {
}
