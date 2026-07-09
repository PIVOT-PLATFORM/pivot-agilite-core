package fr.pivot.agilite.retro.session.dto;

import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;

import java.time.Instant;
import java.util.UUID;

/**
 * Full detail response for an authenticated caller — returned by both {@code POST
 * /retro/sessions} (creation) and {@code GET /retro/sessions/{id}} (detail view, any phase
 * including {@code CLOSED}).
 *
 * <p>Never returned by the public join-resolution endpoint — see {@link
 * RetroSessionJoinResponse} for the deliberately minimal shape exposed there instead.
 *
 * @param id                        session unique identifier
 * @param title                     session title
 * @param format                    retrospective format reference
 * @param teamId                    owning team's {@code public.teams.id}
 * @param facilitatorUserId         facilitator's {@code public.users.id}
 * @param joinCode                  6-character alphanumeric join code
 * @param currentPhase              current lifecycle phase
 * @param contributionTimerSeconds  optional contribution-phase timer, {@code null} if unconfigured
 * @param voteTimerSeconds          optional vote-phase timer, {@code null} if unconfigured
 * @param actionTimerSeconds        optional action-phase timer, {@code null} if unconfigured
 * @param voteCountPerParticipant   number of dot-votes per participant
 * @param sprintRef                 optional free-text sprint reference
 * @param expiresAt                 absolute expiry timestamp
 * @param createdAt                 creation timestamp
 */
public record RetroSessionResponse(
        UUID id,
        String title,
        RetroFormat format,
        Long teamId,
        Long facilitatorUserId,
        String joinCode,
        RetroPhase currentPhase,
        Integer contributionTimerSeconds,
        Integer voteTimerSeconds,
        Integer actionTimerSeconds,
        Integer voteCountPerParticipant,
        String sprintRef,
        Instant expiresAt,
        Instant createdAt) {

    /**
     * Builds the response from a persisted {@link RetroSession} entity.
     *
     * @param session the entity to project
     * @return a populated response record
     */
    public static RetroSessionResponse from(final RetroSession session) {
        return new RetroSessionResponse(
                session.getId(),
                session.getTitle(),
                session.getFormat(),
                session.getTeamId(),
                session.getFacilitatorUserId(),
                session.getJoinCode(),
                session.getCurrentPhase(),
                session.getContributionTimerSeconds(),
                session.getVoteTimerSeconds(),
                session.getActionTimerSeconds(),
                session.getVoteCountPerParticipant(),
                session.getSprintRef(),
                session.getExpiresAt(),
                session.getCreatedAt());
    }
}
