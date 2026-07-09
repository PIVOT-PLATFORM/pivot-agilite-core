package fr.pivot.agilite.wheel.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for fully replacing a wheel's name and entries (US14.1.1).
 *
 * <p>{@code teamId} is not part of this payload — a wheel's team is immutable after creation.
 * {@code entries} fully replaces the wheel's existing entries (additions, removals, and weight
 * changes in one call); an empty list is rejected with {@code EMPTY_ENTRIES}.
 */
public record UpdateWheelRequest(
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 100, message = "INVALID_NAME")
        String name,
        @NotEmpty(message = "EMPTY_ENTRIES")
        List<@Valid WheelEntryRequest> entries) {
}
