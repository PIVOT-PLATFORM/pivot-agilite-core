package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when a caller holding only an anonymous guest access grant — see {@code
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService#requireNonGuest} — attempts an action reserved
 * to the room's authenticated facilitator (US09.3.1).
 *
 * <p>Mapped to HTTP 403 Forbidden by {@code GlobalExceptionHandler}, code {@code
 * FACILITATOR_ONLY_ACTION}. Originally documented as the primitive sibling US09.2.1 (ticket
 * creation) would call to reject an anonymous guest — turned out unnecessary there: ticket
 * creation is a {@code POST /poker/rooms/{roomId}/tickets} REST endpoint requiring a Bearer
 * token (see {@code fr.pivot.agilite.context.RequestPrincipalResolver}), and an account-less
 * guest never holds one at all — it is rejected with a generic 401 before ever reaching a
 * facilitator check, without needing {@link
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService#requireNonGuest} at all. {@code
 * TicketFacilitatorOnlyException} covers US09.2.1's own case instead (an authenticated,
 * same-tenant caller who simply isn't that room's facilitator — never a guest-specific
 * concern). This exception and {@link
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService#requireNonGuest}/{@code #isGuest} remain
 * available infrastructure for any future STOMP-level facilitator-only action (e.g. a reveal
 * trigger, US09.2.2), exactly like {@code RoomAccessGrantService#grantAccess}/{@code #hasAccess}
 * themselves shipped ahead of their first real caller (US09.1.2) for EN09.1.
 */
public class PokerFacilitatorOnlyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a facilitator-only exception for the given room.
     *
     * @param roomId the room whose facilitator-only action an anonymous guest attempted
     */
    public PokerFacilitatorOnlyException(final UUID roomId) {
        super("Guest session is not authorized for facilitator-only actions in room: " + roomId);
    }
}
