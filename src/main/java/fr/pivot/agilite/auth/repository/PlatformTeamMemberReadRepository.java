package fr.pivot.agilite.auth.repository;

import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to {@code public.team_members} (US14.1.1).
 *
 * <p>Extends Spring Data's bare {@link Repository} marker — not {@code JpaRepository} — so no
 * {@code save}/{@code delete} method is ever exposed: this repo never writes to
 * {@code team_members}.
 */
public interface PlatformTeamMemberReadRepository extends Repository<PlatformTeamMember, Long> {

    /**
     * Checks whether a user belongs to a team.
     *
     * @param teamId the {@code public.teams.id}
     * @param userId the {@code public.users.id}
     * @return {@code true} if a membership row exists for this pair
     */
    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    /**
     * Lists every member of a team.
     *
     * @param teamId the {@code public.teams.id}
     * @return all membership rows for that team
     */
    List<PlatformTeamMember> findAllByTeamId(Long teamId);

    /**
     * Finds a membership row by id, scoped to the expected team.
     *
     * <p>Used to validate that a wheel entry's {@code teamMemberId} actually belongs to the
     * wheel's own team, rejecting cross-team references.
     *
     * @param id     the {@code public.team_members.id} to look up
     * @param teamId the expected {@code public.teams.id}
     * @return the matching membership row, or empty if not found or owned by another team
     */
    Optional<PlatformTeamMember> findByIdAndTeamId(Long id, Long teamId);

    /**
     * Lists every team a user belongs to.
     *
     * @param userId the {@code public.users.id}
     * @return all membership rows for that user
     */
    List<PlatformTeamMember> findAllByUserId(Long userId);
}
