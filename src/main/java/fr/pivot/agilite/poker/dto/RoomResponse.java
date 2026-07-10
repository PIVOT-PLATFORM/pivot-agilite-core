package fr.pivot.agilite.poker.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for a planning poker room (US09.1.1), returned by both {@code POST
 * /api/agilite/poker/rooms} and {@code GET /api/agilite/poker/rooms/{roomId}}.
 *
 * <p>{@code id} is a {@code UUID}, not a {@code BIGSERIAL} — required for interop with EN09.1's
 * WebSocket isolation layer ({@code PokerRoomDestinations}/{@code RoomAccessGrantService}, both
 * keyed on {@code UUID roomId}). {@code wsTopic} is built via {@code
 * PokerRoomDestinations#roomTopic(UUID)} — the single source of truth for this destination
 * naming, already fixed by ADR-026 §2 for US09.1.2 ({@code /topic/agilite/poker/{roomId}}).
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
        UUID id,
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
