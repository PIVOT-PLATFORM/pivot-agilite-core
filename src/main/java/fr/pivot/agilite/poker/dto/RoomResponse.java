package fr.pivot.agilite.poker.dto;

import java.time.Instant;
import java.util.List;

/**
 * API response shape for a planning poker room (US09.1.1), returned by both {@code POST
 * /api/agilite/poker/rooms} and {@code GET /api/agilite/poker/rooms/{roomId}}.
 *
 * <p>{@code wsTopic} follows the STOMP destination convention already fixed by ADR-026 §2 for
 * US09.1.2 ({@code /topic/agilite/poker/{roomId}}) — the frontend subscribes to this exact
 * destination once it joins the room. To be reconfirmed against EN09.1 (WebSocket room
 * isolation, developed in parallel in this same repo) once merged, in case its isolation
 * mechanism changes the destination naming convention.
 *
 * @param id                room primary key
 * @param name              room display name
 * @param inviteCode        6-character invite code
 * @param sequence          fixed card sequence identifier, always {@code "FIBONACCI"} in v1
 * @param cardValues        the fixed card values for {@code sequence}
 * @param facilitatorUserId the creator's user id
 * @param active            whether the room is still active
 * @param createdAt         creation timestamp
 * @param expiresAt         expiry timestamp
 * @param wsTopic           the STOMP destination this room's participants subscribe to
 */
public record RoomResponse(
        Long id,
        String name,
        String inviteCode,
        String sequence,
        List<String> cardValues,
        Long facilitatorUserId,
        boolean active,
        Instant createdAt,
        Instant expiresAt,
        String wsTopic) {
}
