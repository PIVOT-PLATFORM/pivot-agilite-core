package fr.pivot.agilite.wheel.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for a successful {@code POST /wheels/{wheelId}/spin} (US14.2.1).
 *
 * <p>This exact shape is the one US14.3.1 (real-time WebSocket broadcast of the draw result,
 * depends on this US) consumes as-is for the {@code SPIN_RESULT} STOMP message payload.
 *
 * @param wheelId        the drawn-from wheel's identifier
 * @param entryId        the drawn entry's identifier
 * @param label          the drawn entry's display label
 * @param drawnAt        the timestamp of the draw
 * @param antiRepeatMode the anti-repeat mode actually applied to this draw ({@code "exclude"} or
 *                       {@code "reduced_weight"})
 */
public record WheelSpinResponse(
        UUID wheelId,
        UUID entryId,
        String label,
        Instant drawnAt,
        String antiRepeatMode) {
}
