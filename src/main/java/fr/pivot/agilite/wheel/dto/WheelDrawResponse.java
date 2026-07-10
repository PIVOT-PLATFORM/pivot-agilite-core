package fr.pivot.agilite.wheel.dto;

import fr.pivot.agilite.wheel.WheelDraw;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload for a single row of {@code GET /wheels/{wheelId}/draws} (US14.2.1).
 *
 * <p>No {@code wheelId} field — already carried by the request path, not repeated per element.
 *
 * @param entryId the drawn entry's identifier, or {@code null} if it has since been removed from
 *                the wheel
 * @param label   the drawn entry's display label, frozen at draw time
 * @param drawnAt the timestamp of the draw
 */
public record WheelDrawResponse(UUID entryId, String label, Instant drawnAt) {

    /**
     * Factory method that creates a {@link WheelDrawResponse} from a {@link WheelDraw} entity.
     *
     * @param draw the draw entity
     * @return a populated response record
     */
    public static WheelDrawResponse from(final WheelDraw draw) {
        return new WheelDrawResponse(draw.getEntryId(), draw.getEntryLabel(), draw.getDrawnAt());
    }
}
