package fr.pivot.agilite.retro.format.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating a tenant-owned custom retrospective format ({@code POST
 * /retro/formats}, US20.2.1).
 *
 * @param label   format-level label, 1-60 characters
 * @param columns the format's columns, 2 to 8 entries — the exact "format CUSTOM sans colonnes
 *                définies" AC case (0 or 1 column) 400s with {@code
 *                CUSTOM_FORMAT_INVALID_COLUMN_COUNT}
 */
public record CreateRetroFormatRequest(
        @NotBlank(message = "INVALID_FORMAT_LABEL")
        @Size(min = 1, max = 60, message = "INVALID_FORMAT_LABEL")
        String label,

        @NotNull(message = "CUSTOM_FORMAT_INVALID_COLUMN_COUNT")
        @Size(min = 2, max = 8, message = "CUSTOM_FORMAT_INVALID_COLUMN_COUNT")
        List<@Valid CreateRetroFormatColumnRequest> columns) {
}
