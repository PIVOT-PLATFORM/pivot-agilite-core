package fr.pivot.agilite.poker.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/agilite/poker/rooms/{roomId}/guest-sessions/heartbeat}
 * (US09.3.1) — keeps an anonymous guest's 2h-inactivity session alive past its current TTL.
 *
 * <p>Deliberately has no counterpart to {@code RequestPrincipal} — like {@link
 * AnonymousJoinRequest}, this endpoint accepts no bearer token; the {@code accessToken} carried
 * here is the sole credential checked, against the Redis-backed grant store (see {@code
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService}), never against any user identity.
 *
 * @param accessToken the access token issued by {@code POST .../join-anonymous}
 */
public record GuestHeartbeatRequest(
        @NotBlank(message = "INVALID_ACCESS_TOKEN")
        String accessToken) {
}
