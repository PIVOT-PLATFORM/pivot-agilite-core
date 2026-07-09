package fr.pivot.agilite.wheel.dto;

import fr.pivot.agilite.wheel.WheelEntryType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for a single wheel entry (US14.1.1).
 *
 * <p>For {@link WheelEntryType#TEAM_MEMBER}, {@code teamMemberId} is required and any supplied
 * {@code label} is ignored (server-resolved from {@code public.users}); for {@link
 * WheelEntryType#FREE_TEXT}, {@code label} is required and {@code teamMemberId} must be omitted.
 * This conditional requirement can't be expressed with simple bean validation annotations and is
 * enforced in {@code WheelService}. {@code weight} defaults to 1 (equal weighting) when omitted;
 * when present it must be 1-10.
 */
public record WheelEntryRequest(
        @NotNull(message = "INVALID_ENTRY")
        WheelEntryType type,
        Long teamMemberId,
        String label,
        @Min(value = 1, message = "INVALID_WEIGHT")
        @Max(value = 10, message = "INVALID_WEIGHT")
        Integer weight) {
}
