package fr.pivot.agilite.retro.action.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for changing a retro action's status ({@code PATCH /retro/actions/{actionId}},
 * US20.3.1).
 *
 * <p>{@code status} is intentionally typed as {@code String}, not the {@code RetroActionStatus}
 * enum directly — same rationale as {@code CreateRetroSessionRequest#format}: Jackson enum
 * binding would reject an unknown value with a generic 400 before reaching the service layer,
 * whereas {@code RetroActionService} validates it explicitly and throws a dedicated {@code
 * InvalidRetroActionStatusException} (400 with machine-readable {@code INVALID_ACTION_STATUS}
 * code).
 *
 * @param status the raw status value, validated against {@link
 *               fr.pivot.agilite.retro.action.RetroActionStatus} in the service
 */
public record UpdateRetroActionStatusRequest(
        @NotBlank(message = "INVALID_ACTION_STATUS")
        String status) {
}
