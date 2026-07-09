package fr.pivot.agilite.team.dto;

/**
 * Response payload representing a team the caller belongs to (US14.1.1).
 *
 * @param id   {@code public.teams.id}
 * @param name the team's display name
 */
public record TeamResponse(Long id, String name) {
}
