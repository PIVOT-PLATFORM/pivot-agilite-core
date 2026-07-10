package fr.pivot.agilite.poker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/agilite/poker/rooms/join-anonymous} (US09.3.1).
 *
 * <p>Deliberately has no counterpart to {@code RequestPrincipal} — this endpoint accepts no
 * bearer token at all ({@code PokerRoomController#joinAnonymous} declares no {@code
 * RequestPrincipal} parameter, so no attempt is made to resolve an {@code Authorization} header).
 * {@code pseudonym} is optional: Bean Validation treats a {@code null} {@code String} as valid
 * for {@code @Size} (same convention as {@code CreateRoomRequest#expirationHours}), so omitting
 * it never fails validation — {@code PokerRoomService#joinAnonymous} substitutes a generated
 * default when absent or blank.
 *
 * @param code      the 6-character invite code to resolve (same validation as {@link
 *                  JoinRoomRequest#code()})
 * @param pseudonym optional display name, trimmed server-side, max 40 characters
 */
public record AnonymousJoinRequest(
        @NotBlank(message = "INVALID_CODE")
        @Size(min = 6, max = 6, message = "INVALID_CODE")
        String code,

        @Size(max = 40, message = "INVALID_PSEUDONYM")
        String pseudonym) {
}
