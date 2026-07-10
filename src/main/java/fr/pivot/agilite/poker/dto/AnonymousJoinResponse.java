package fr.pivot.agilite.poker.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for {@code POST /api/agilite/poker/rooms/join-anonymous} (US09.3.1).
 *
 * <p>Mirrors {@link JoinRoomResponse} (US09.1.2) with two additions specific to anonymous
 * participation: {@code sessionId} — a per-join, server-generated correlation id, never
 * persisted anywhere (no JPA entity, no database row — ADR-026 §2) — and {@code pseudonym}, the
 * resolved display name (either caller-supplied, trimmed, or server-generated). {@code
 * guestSessionExpiresAt} is the 2h-inactivity-capped expiry of the underlying access grant,
 * distinct from {@code expiresAt} (the room's own lifetime, which may be shorter and always
 * takes precedence — see {@code PokerRoomService#joinAnonymous}).
 *
 * @param roomId                room primary key
 * @param name                  room display name
 * @param sequence              fixed card sequence identifier, always {@code "FIBONACCI"} in v1
 * @param cardValues            the fixed card values for {@code sequence}
 * @param active                whether the room is still active
 * @param expiresAt             the room's own expiry timestamp
 * @param wsTopic               the STOMP destination this room's participants subscribe to
 * @param accessToken           the opaque access token authorizing this guest on {@code wsTopic}
 * @param sessionId             a temporary, server-generated correlation id — never persisted
 * @param pseudonym             the resolved display name for this guest
 * @param guestSessionExpiresAt when this guest session's access grant expires absent a heartbeat
 */
public record AnonymousJoinResponse(
        UUID roomId,
        String name,
        String sequence,
        List<String> cardValues,
        boolean active,
        Instant expiresAt,
        String wsTopic,
        String accessToken,
        String sessionId,
        String pseudonym,
        Instant guestSessionExpiresAt) {
}
