package fr.pivot.agilite.poker.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for {@code POST /api/agilite/poker/rooms/join} (US09.1.2).
 *
 * <p>Deliberately excludes {@code inviteCode} and {@code facilitatorUserId} — unlike {@link
 * RoomResponse}, this response is returned to <em>any</em> participant who successfully joined
 * via code, not just the facilitator; re-exposing the invite code here would let a participant
 * trivially reshare it verbatim from the join response, and the facilitator's identity is not
 * needed by a joining participant. {@code accessToken} is the opaque, room-scoped grant minted by
 * {@code PokerRoomService#join} and already registered with {@code RoomAccessGrantService} — the
 * client presents it as the {@code access-token} STOMP native header (see {@code
 * PokerChannelInterceptor}) to subscribe to {@code wsTopic}.
 *
 * @param roomId     room primary key
 * @param name       room display name
 * @param sequence   fixed card sequence identifier, always {@code "FIBONACCI"} in v1
 * @param cardValues the fixed card values for {@code sequence}
 * @param active     whether the room is still active
 * @param expiresAt  expiry timestamp
 * @param wsTopic    the STOMP destination this room's participants subscribe to
 * @param accessToken the opaque access token authorizing this participant on {@code wsTopic}
 */
public record JoinRoomResponse(
        UUID roomId,
        String name,
        String sequence,
        List<String> cardValues,
        boolean active,
        Instant expiresAt,
        String wsTopic,
        String accessToken) {
}
