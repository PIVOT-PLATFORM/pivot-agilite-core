package fr.pivot.agilite.poker.exception;

/**
 * Thrown when an anonymous guest session's {@code accessToken} (US09.3.1) does not resolve to a
 * currently valid access grant — either it was never issued, it has already expired (2h
 * inactivity cap, ADR-026 §2), or the room it was issued for has since become inactive or
 * expired.
 *
 * <p>Mapped to HTTP 410 Gone by {@code GlobalExceptionHandler} — the session existed but is no
 * longer usable; the caller must rejoin via {@code POST /poker/rooms/join-anonymous} to obtain a
 * fresh one. These causes are deliberately never distinguished — mirrors {@link
 * InviteCodeNotFoundException}'s posture of collapsing every rejection reason into a single,
 * uninformative response.
 */
public class GuestSessionExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a guest session that is no longer valid, for whichever of the
     * collapsed reasons applies (never issued, expired, or room no longer active).
     */
    public GuestSessionExpiredException() {
        super("Guest session expired or invalid");
    }
}
