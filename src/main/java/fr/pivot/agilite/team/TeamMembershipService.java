package fr.pivot.agilite.team;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformTeamReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.exception.TeamNotFoundException;
import fr.pivot.agilite.team.dto.TeamMemberResponse;
import fr.pivot.agilite.team.dto.TeamResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read-only team/team-membership operations shared by the wheel feature and the {@code
 * /teams} endpoints (US14.1.1) — the differentiator of this module: importing entrants
 * natively from {@code public.teams}/{@code public.team_members} rather than manual re-entry.
 *
 * <p>{@link #resolveTeamForCaller(Long, Long, Long)} is the single team-resolution +
 * membership-check helper reused by {@code WheelService} so every wheel operation applies the
 * exact same anti-enumeration rule as the {@code /teams} endpoints themselves.
 */
@Service
@Transactional(readOnly = true)
public class TeamMembershipService {

    private final PlatformTeamReadRepository teamRepository;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final PlatformUserReadRepository userRepository;

    /**
     * Constructs the service with its read-only repositories.
     *
     * @param teamRepository       read-only access to {@code public.teams}
     * @param teamMemberRepository read-only access to {@code public.team_members}
     * @param userRepository       read-only access to {@code public.users}
     */
    public TeamMembershipService(
            final PlatformTeamReadRepository teamRepository,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final PlatformUserReadRepository userRepository) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Resolves a team, verifying it belongs to the caller's tenant and that the caller is one
     * of its members.
     *
     * @param teamId       the {@code public.teams.id} to resolve
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved team
     * @throws TeamNotFoundException if the team does not exist, belongs to another tenant, or
     *     the caller is not one of its members
     */
    public PlatformTeam resolveTeamForCaller(final Long teamId, final Long callerUserId, final Long tenantId) {
        PlatformTeam team = teamRepository.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        if (!teamMemberRepository.existsByTeamIdAndUserId(teamId, callerUserId)) {
            throw new TeamNotFoundException(teamId);
        }
        return team;
    }

    /**
     * Lists the members of a team, resolving each member's display name.
     *
     * @param teamId       the {@code public.teams.id}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the team's members
     * @throws TeamNotFoundException if the team does not exist, belongs to another tenant, or
     *     the caller is not one of its members
     */
    public List<TeamMemberResponse> listMembers(final Long teamId, final Long callerUserId, final Long tenantId) {
        resolveTeamForCaller(teamId, callerUserId, tenantId);
        return teamMemberRepository.findAllByTeamId(teamId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    /**
     * Lists the teams the caller belongs to, scoped defensively to the caller's own tenant.
     *
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the caller's teams
     */
    public List<TeamResponse> listMyTeams(final Long callerUserId, final Long tenantId) {
        return teamMemberRepository.findAllByUserId(callerUserId).stream()
                .map(PlatformTeamMember::getTeamId)
                .distinct()
                .map(teamId -> teamRepository.findByIdAndTenantId(teamId, tenantId))
                .flatMap(Optional::stream)
                .map(team -> new TeamResponse(team.getId(), team.getName()))
                .toList();
    }

    /**
     * Resolves a team member's display name: first + last name if both are non-blank, otherwise
     * the email address.
     *
     * @param teamMember the membership row
     * @return a {@link TeamMemberResponse} with the resolved display name
     */
    private TeamMemberResponse toMemberResponse(final PlatformTeamMember teamMember) {
        Optional<PlatformUser> userOpt = userRepository.findById(teamMember.getUserId());
        String displayName = userOpt.map(this::resolveDisplayName).orElse("");
        return new TeamMemberResponse(teamMember.getId(), teamMember.getUserId(), displayName);
    }

    /**
     * Resolves a user's display name: first + last name if both are non-blank, otherwise the
     * email address.
     *
     * @param user the platform user
     * @return the resolved display name
     */
    public String resolveDisplayName(final PlatformUser user) {
        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        if (firstName != null && !firstName.isBlank() && lastName != null && !lastName.isBlank()) {
            return firstName.trim() + " " + lastName.trim();
        }
        return user.getEmail();
    }
}
