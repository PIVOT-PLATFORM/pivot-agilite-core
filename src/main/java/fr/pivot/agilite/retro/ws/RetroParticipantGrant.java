package fr.pivot.agilite.retro.ws;

/**
 * Identity carried by a retrospective session access grant (US20.1.2a).
 *
 * <p><strong>Never trust anything client-supplied for these fields.</strong> A {@code
 * RetroParticipantGrant} is only ever constructed server-side, at the moment {@code
 * RetroSessionAccessService} mints a grant — {@code userId}/{@code tenantId} come from a bearer
 * token validated against {@code public.access_tokens} (never from a client-claimed header/body
 * field), and {@code facilitator} is computed by comparing that resolved {@code userId} against
 * the session's own {@code facilitatorUserId} row, never accepted as a caller-supplied flag.
 *
 * <p><strong>Anonymous, account-less participants are a first-class case</strong> (US20.1.1's
 * deliberately frictionless join-by-code design — "participant sans compte inclus"): both {@code
 * userId} and {@code tenantId} are {@code null} for a participant who joined without presenting a
 * valid bearer token, or whose token belongs to a different tenant than the session's own (cross-
 * tenant identity is never attached to a card — see {@code RetroSessionAccessService}). Such a
 * participant can still submit cards; they are simply always recorded without an author (see
 * {@code RetroCardService#submit}), exactly like an explicit {@code anonymous} submission.
 *
 * @param userId      the participant's {@code public.users.id}, or {@code null} if anonymous /
 *                    account-less
 * @param tenantId    the participant's {@code public.tenants.id}, or {@code null} if anonymous
 * @param facilitator whether this participant is the session's facilitator — informational only
 *                    (the REST endpoints that gate facilitator-only actions re-verify identity
 *                    independently via the bearer token, never trusting this flag alone)
 */
public record RetroParticipantGrant(Long userId, Long tenantId, boolean facilitator) {

    /** Grant shape for an anonymous, account-less participant (never a facilitator). */
    public static RetroParticipantGrant anonymous() {
        return new RetroParticipantGrant(null, null, false);
    }
}
