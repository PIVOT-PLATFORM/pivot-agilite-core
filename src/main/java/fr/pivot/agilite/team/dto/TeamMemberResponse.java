package fr.pivot.agilite.team.dto;

/**
 * Response payload representing a member of a team (US14.1.1).
 *
 * <p>{@code id} is the {@code public.team_members.id} — the value to reference as a wheel
 * entry's {@code teamMemberId}, distinct from {@code userId}.
 *
 * @param id          {@code public.team_members.id}
 * @param userId      the member's {@code public.users.id}
 * @param displayName resolved display name (first + last name, or email as fallback)
 */
public record TeamMemberResponse(Long id, Long userId, String displayName) {
}
