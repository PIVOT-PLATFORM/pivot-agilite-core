package fr.pivot.agilite.wheel;

import com.fasterxml.jackson.annotation.JsonProperty;

import fr.pivot.agilite.exception.WheelValidationException;

/**
 * Anti-repeat strategy applied to the last-drawn entry of a wheel during a weighted draw
 * (US14.2.1).
 *
 * <p>This is a per-{@code spin}-request parameter, not a field persisted on {@link Wheel} — see
 * the Gate 1 clarification in the backlog AC (US14.2.1, {@code us-tirage-pondere.md}): the stub
 * did not specify where this configuration lived, and {@code wheel} (US14.1.1) has no such field.
 */
public enum AntiRepeatMode {

    /** The last-drawn entry is fully excluded from the draw pool (effective weight 0). */
    @JsonProperty("exclude")
    EXCLUDE("exclude"),

    /**
     * The last-drawn entry's effective weight is reduced to {@code max(1, weight / 5)} (integer
     * division) instead of being excluded outright. Default mode when a {@code spin} request
     * omits {@code antiRepeatMode}.
     */
    @JsonProperty("reduced_weight")
    REDUCED_WEIGHT("reduced_weight");

    private final String jsonValue;

    AntiRepeatMode(final String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /**
     * Returns the lowercase wire value of this mode, as used in the {@code spin} request/response
     * JSON bodies.
     *
     * @return the wire value ({@code "exclude"} or {@code "reduced_weight"})
     */
    public String jsonValue() {
        return jsonValue;
    }

    /**
     * Resolves a mode from its raw wire value.
     *
     * @param raw the raw {@code antiRepeatMode} string from the request body
     * @return the matching mode
     * @throws WheelValidationException with code {@code INVALID_ANTI_REPEAT_MODE} if {@code raw}
     *     is not one of the known wire values
     */
    public static AntiRepeatMode fromJsonValue(final String raw) {
        for (AntiRepeatMode mode : values()) {
            if (mode.jsonValue.equals(raw)) {
                return mode;
            }
        }
        throw new WheelValidationException(
                "INVALID_ANTI_REPEAT_MODE", "Unknown antiRepeatMode: " + raw);
    }
}
