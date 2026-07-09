package fr.pivot.agilite.wheel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for creating a new wheel (US14.1.1).
 *
 * <p>{@code teamId} is the wheel's owning team, immutable after creation. {@code name} must be
 * 1-100 characters. {@code entries} must contain at least one element — an empty list is
 * rejected with {@code EMPTY_ENTRIES} (a wheel is never created with zero entrants).
 */
public record CreateWheelRequest(
        @NotNull(message = "INVALID_TEAM")
        Long teamId,
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 100, message = "INVALID_NAME")
        String name,
        @NotEmpty(message = "EMPTY_ENTRIES")
        List<@Valid WheelEntryRequest> entries) {
}
