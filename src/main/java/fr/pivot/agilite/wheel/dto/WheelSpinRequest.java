package fr.pivot.agilite.wheel.dto;

/**
 * Request body for {@code POST /wheels/{wheelId}/spin} (US14.2.1).
 *
 * <p>{@code antiRepeatMode} is optional — the request body itself may be omitted entirely
 * (empty/absent) or sent as {@code {}}. The raw string is validated and resolved by {@code
 * WheelDrawService} (never bound directly to the {@code AntiRepeatMode} enum here) so an unknown
 * value produces the documented {@code INVALID_ANTI_REPEAT_MODE} code rather than a generic
 * deserialization failure.
 *
 * @param antiRepeatMode {@code "exclude"} or {@code "reduced_weight"}; {@code null} (field
 *                       omitted) defaults to {@code "reduced_weight"} server-side
 */
public record WheelSpinRequest(String antiRepeatMode) {
}
