package fr.pivot.agilite.retro.ws.dto;

/**
 * Response returned by {@code POST /retro/sessions/{id}/participants} — the access grant a
 * client needs before it can SUBSCRIBE/SEND on the session's realtime STOMP channel (US20.1.2a).
 *
 * @param accessToken                  opaque token to present as the {@code access-token} native
 *                                     STOMP header on every SUBSCRIBE/SEND for this session
 * @param ttlSeconds                   how long the grant remains valid, in seconds
 * @param facilitator                  whether the caller was resolved as this session's
 *                                     facilitator (informational — the REST endpoints gating
 *                                     facilitator-only actions re-verify identity independently)
 * @param topicDestination             the destination to subscribe to for masked card counts,
 *                                     phase changes, and revealed cards ({@code CARD_ADDED}
 *                                     masked / {@code PHASE_CHANGED} / {@code CARDS_REVEALED})
 * @param facilitatorTopicDestination  the facilitator-only preview destination (full,
 *                                     un-masked {@code CARD_ADDED}), or {@code null} if the
 *                                     caller is not the facilitator
 * @param submitDestination            the destination to SEND a new card submission to
 */
public record RetroParticipantAccessResponse(
        String accessToken,
        long ttlSeconds,
        boolean facilitator,
        String topicDestination,
        String facilitatorTopicDestination,
        String submitDestination) {
}
