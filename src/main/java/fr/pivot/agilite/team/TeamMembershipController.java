package fr.pivot.agilite.team;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.team.dto.TeamMemberResponse;
import fr.pivot.agilite.team.dto.TeamResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing read-only team membership operations under {@code /teams}
 * (US14.1.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into
 * a {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 * Missing, malformed, or rejected tokens result in HTTP 401. Both endpoints are read-only
 * consumption of {@code public.teams}/{@code public.team_members} (owned by {@code pivot-core})
 * — this module never writes to either table.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/teams}.
 */
@RestController
@RequestMapping("/teams")
public class TeamMembershipController {

    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param teamMembershipService the team membership business logic service
     */
    public TeamMembershipController(final TeamMembershipService teamMembershipService) {
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Lists the teams the caller belongs to — needed by the Angular UI to let the user choose
     * a {@code teamId} before creating/listing wheels, since {@code pivot-core} does not yet
     * expose this list itself (gap EN17.3, {@code @pivot/ui-core} not consumed).
     *
     * @param principal the resolved caller identity
     * @return the caller's teams
     */
    @GetMapping
    public List<TeamResponse> listMyTeams(final RequestPrincipal principal) {
        return teamMembershipService.listMyTeams(principal.userId(), principal.tenantId());
    }

    /**
     * Lists the members of a team, for the wheel entry "native import" picker.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param principal the resolved caller identity
     * @return the team's members, or HTTP 404 if the team does not exist, belongs to another
     *     tenant, or the caller is not one of its members
     */
    @GetMapping("/{teamId}/members")
    public List<TeamMemberResponse> listMembers(
            @PathVariable final Long teamId,
            final RequestPrincipal principal) {
        return teamMembershipService.listMembers(teamId, principal.userId(), principal.tenantId());
    }
}
