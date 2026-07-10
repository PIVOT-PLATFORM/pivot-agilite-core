package fr.pivot.agilite.poker.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /api/agilite/poker/rooms/{roomId}/guest-sessions/heartbeat}
 * (US09.3.1).
 *
 * @param expiresAt the refreshed guest session expiry — {@code now + 2h}, capped by the room's
 *                  own expiry, whichever comes first
 */
public record GuestHeartbeatResponse(Instant expiresAt) {
}
