package fr.pivot.agilite.retro.session.dto;

import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal response returned by the deliberately public, unauthenticated join-resolution endpoint
 * ({@code GET /retro/sessions/join/{joinCode}}).
 *
 * <p>Exposes only what a not-yet-authenticated participant needs to confirm they are joining the
 * right session — never {@code teamId}, never {@code tenantId}, never the facilitator's
 * identity, never any card content (none exists at this point in the flow anyway). See
 * {@link RetroSessionResponse} for the full detail shape reserved for authenticated tenant
 * members.
 *
 * @param id           session unique identifier
 * @param title        session title
 * @param format       retrospective format reference
 * @param currentPhase current lifecycle phase
 * @param expiresAt    absolute expiry timestamp
 */
public record RetroSessionJoinResponse(
        UUID id,
        String title,
        RetroFormat format,
        RetroPhase currentPhase,
        Instant expiresAt) {

    /**
     * Builds the minimal join response from a persisted {@link RetroSession} entity.
     *
     * @param session the entity to project
     * @return a populated response record
     */
    public static RetroSessionJoinResponse from(final RetroSession session) {
        return new RetroSessionJoinResponse(
                session.getId(),
                session.getTitle(),
                session.getFormat(),
                session.getCurrentPhase(),
                session.getExpiresAt());
    }
}
