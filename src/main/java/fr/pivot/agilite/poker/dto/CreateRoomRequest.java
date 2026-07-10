package fr.pivot.agilite.poker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/agilite/poker/rooms} (US09.1.1).
 *
 * <p>{@code name} is mandatory (1 to 120 characters). {@code expirationHours} is optional —
 * when absent, {@code PokerRoomService} applies its configured default (24h); when present, it
 * must be between 1 and 168 (7 days) inclusive — Bean Validation treats a {@code null} {@code
 * Integer} as valid for {@code @Min}/{@code @Max}, so omitting the field never fails validation.
 * Failures are handled by {@code GlobalExceptionHandler}, returning HTTP 400 with {@code
 * { "code": "INVALID_NAME" } } or {@code { "code": "INVALID_EXPIRATION" } }.
 *
 * <p>Deliberately excludes {@code tenantId}/{@code facilitatorUserId}/{@code sequence} — the
 * first two are resolved exclusively from the caller's bearer token ({@code RequestPrincipal}),
 * the third is fixed to Fibonacci for every room in v1 (ADR-026 §2) — accepting any of them here
 * would be either an IDOR vector or a silent v1-scope violation.
 *
 * @param name            the room's display name (1-120 characters, required)
 * @param expirationHours optional room lifetime in hours (1-168), defaults to 24h when absent
 */
public record CreateRoomRequest(
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,

        @Min(value = 1, message = "INVALID_EXPIRATION")
        @Max(value = 168, message = "INVALID_EXPIRATION")
        Integer expirationHours) {
}
