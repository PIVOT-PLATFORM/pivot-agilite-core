package fr.pivot.agilite.poker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/agilite/poker/rooms/join} (US09.1.2).
 *
 * <p>{@code code} is the 6-character invite code generated at room creation ({@link
 * fr.pivot.agilite.poker.InviteCodeGenerator}). Failures are handled by {@code
 * GlobalExceptionHandler}, returning HTTP 400 with {@code { "code": "INVALID_CODE" } } — mirroring
 * {@link CreateRoomRequest}'s message-as-code convention.
 *
 * <p>Deliberately excludes {@code tenantId}/{@code userId} — both are resolved exclusively from
 * the caller's bearer token ({@code RequestPrincipal}); accepting either here would be an IDOR
 * vector.
 *
 * @param code the 6-character invite code to resolve
 */
public record JoinRoomRequest(
        @NotBlank(message = "INVALID_CODE")
        @Size(min = 6, max = 6, message = "INVALID_CODE")
        String code) {
}
