package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when an authenticated, same-tenant caller attempts a facilitator-only planning poker
 * ticket action (creating a ticket) but is not that room's facilitator (US09.2.1).
 *
 * <p>Mapped to HTTP 403 Forbidden by {@code GlobalExceptionHandler}, code {@code
 * FACILITATOR_ONLY} — safe to disclose the action is restricted here since tenant match (and
 * room existence) is already established by the time this is thrown (unlike {@link
 * RoomNotFoundException}, which covers the cross-tenant/non-existent cases).
 *
 * <p>Deliberately a distinct class from {@link PokerFacilitatorOnlyException} (US09.3.1, code
 * {@code FACILITATOR_ONLY_ACTION}) even though both map to HTTP 403 — that one is specifically
 * for an anonymous guest attempting a facilitator-only action over STOMP; this one is for an
 * authenticated caller who simply isn't the ticket-owning room's facilitator, reached over REST.
 * An account-less guest can never even reach the REST endpoint this exception guards (no bearer
 * token to resolve, rejected with a generic 401 first) — see {@link PokerFacilitatorOnlyException}
 * 's own Javadoc for that reasoning. Also deliberately not reused from {@code
 * fr.pivot.agilite.exception.RetroFacilitatorOnlyException} — distinct modules, distinct resource
 * kind (room, not retro session).
 */
public class TicketFacilitatorOnlyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a facilitator-only exception for the given room.
     *
     * @param roomId the room whose facilitator-only ticket action was attempted
     */
    public TicketFacilitatorOnlyException(final UUID roomId) {
        super("Caller is not the facilitator of room: " + roomId);
    }
}
