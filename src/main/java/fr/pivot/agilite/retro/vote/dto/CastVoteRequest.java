package fr.pivot.agilite.retro.vote.dto;

import java.util.UUID;

/**
 * Payload sent by a participant casting or removing a dot-vote (US20.1.2b), over STOMP SEND to
 * either {@code /app/agilite/retro/{sessionId}/votes} (cast) or
 * {@code /app/agilite/retro/{sessionId}/votes/uncast} (uncast) — the same shape serves both.
 *
 * @param cardId the target card's identifier, expected to belong to the session addressed by the
 *               destination
 */
public record CastVoteRequest(UUID cardId) {
}
