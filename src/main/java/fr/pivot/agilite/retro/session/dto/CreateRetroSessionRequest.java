package fr.pivot.agilite.retro.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new retrospective session ({@code POST /retro/sessions}).
 *
 * <p>{@code format} is intentionally typed as {@code String}, not the {@code RetroFormat} enum
 * directly — Jackson enum binding would reject an unknown value with a generic 400 before
 * reaching the service layer, whereas {@code RetroSessionService} validates it explicitly
 * against the enum's known constants and throws a dedicated {@code InvalidRetroFormatException}
 * (400 with machine-readable {@code INVALID_FORMAT} code).
 *
 * <p>The three timer fields and {@code voteCountPerParticipant} are optional (nullable
 * {@link Integer}); {@code @Positive} already treats {@code null} as valid, so this correctly
 * expresses "optional, but must be strictly positive if present." {@code voteCountPerParticipant}
 * defaults to 3 in the service when not supplied.
 *
 * @param title                    session title, 1–100 characters
 * @param format                   raw format reference, validated against {@code RetroFormat} in
 *                                 the service
 * @param teamId                   the team this session belongs to ({@code public.teams.id})
 * @param sprintRef                optional free-text sprint reference, max 100 characters
 * @param contributionTimerSeconds optional contribution-phase timer in seconds, must be positive
 *                                 if present
 * @param voteTimerSeconds         optional vote-phase timer in seconds, must be positive if
 *                                 present
 * @param actionTimerSeconds       optional action-phase timer in seconds, must be positive if
 *                                 present
 * @param voteCountPerParticipant  optional number of dot-votes per participant, must be positive
 *                                 if present; defaults to 3 in the service when {@code null}
 */
public record CreateRetroSessionRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 100, message = "INVALID_TITLE")
        String title,

        @NotBlank(message = "INVALID_FORMAT")
        String format,

        @NotNull(message = "INVALID_TEAM_ID")
        Long teamId,

        @Size(max = 100, message = "INVALID_SPRINT_REF")
        String sprintRef,

        @Positive(message = "INVALID_TIMER")
        Integer contributionTimerSeconds,

        @Positive(message = "INVALID_TIMER")
        Integer voteTimerSeconds,

        @Positive(message = "INVALID_TIMER")
        Integer actionTimerSeconds,

        @Positive(message = "INVALID_VOTE_COUNT")
        Integer voteCountPerParticipant) {
}
