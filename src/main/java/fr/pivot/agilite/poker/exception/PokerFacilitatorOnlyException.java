package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when a caller holding only an anonymous guest access grant — see {@code
 * fr.pivot.agilite.poker.ws.RoomAccessGrantService#requireNonGuest} — attempts an action reserved
 * to the room's authenticated facilitator (US09.3.1).
 *
 * <p>Mapped to HTTP 403 Forbidden by {@code GlobalExceptionHandler}, code {@code
 * FACILITATOR_ONLY_ACTION}. This is the primitive that facilitator-only actions introduced by
 * sibling US09.2.1 (ticket creation) / US09.2.2 (reveal) must call to reject an anonymous guest —
 * those endpoints do not exist in this repo yet (same sprint wave, no dependency on this US, see
 * {@code pivot-docs/docs/backlog/sprints/sprint-8.md}). This exception and {@code
 * RoomAccessGrantService#requireNonGuest}/{@code #isGuest} define the contract now, exactly like
 * {@code RoomAccessGrantService#grantAccess}/{@code #hasAccess} themselves did for EN09.1 before
 * US09.1.2 (their first real caller) existed.
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
