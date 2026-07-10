package fr.pivot.agilite.poker.exception;

/**
 * Thrown when an invite {@code code} supplied to the join-by-code endpoint (US09.1.2) does not
 * resolve to a currently joinable planning poker room.
 *
 * <p>Mapped to HTTP 404 Not Found by {@code GlobalExceptionHandler}. The message deliberately
 * never echoes the raw code back into the response (mirrors {@code RetroJoinCodeNotFoundException}
 * 's style).
 *
 * <p><strong>Security AC (ADR-026 §2):</strong> this single exception — and therefore a single,
 * indistinguishable 404 response — covers <em>all</em> of: an unknown code, a code belonging to a
 * room in a different tenant, a code for a deactivated room, and a code for an expired room. These
 * four causes are deliberately never distinguished, unlike {@code RetroSessionExpiredException}'s
 * separate 410 Gone for the retro-session join flow — planning poker's join-by-code endpoint uses
 * 404 uniformly for every rejection reason, so a caller can never learn which of the four applies.
 */
public class InviteCodeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a not-found exception for an invite code that is not currently joinable, for
     * whichever of the collapsed reasons applies (unknown, cross-tenant, inactive, or expired).
     */
    public InviteCodeNotFoundException() {
        super("Invite code not found");
    }
}
