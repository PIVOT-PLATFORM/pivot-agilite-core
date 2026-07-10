package fr.pivot.agilite.retro.action;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.retro.action.dto.CreateRetroActionRequest;
import fr.pivot.agilite.retro.action.dto.RetroActionResponse;
import fr.pivot.agilite.retro.action.dto.UpdateRetroActionStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing retrospective action operations (US20.3.1).
 *
 * <p>Full paths (including the application context): {@code /api/agilite/retro/sessions/{id}/
 * actions}, {@code /api/agilite/retro/actions/{actionId}}, {@code /api/agilite/retro/teams/
 * {teamId}/actions}. Each spans a different resource root ({@code retro/sessions}, {@code
 * retro/actions}, {@code retro/teams}), unlike {@code RetroPhaseController}/{@code
 * RetroSessionController} which each map a single shared prefix — so no class-level {@code
 * @RequestMapping} is used here, and every method carries its own full path instead.
 *
 * <p>Every endpoint requires a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} exactly like {@code RetroSessionController}/{@code
 * RetroPhaseController} — no anonymous path exists for action management, unlike card submission.
 */
@RestController
public class RetroActionController {

    private final RetroActionService actionService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param actionService the retro action business logic service
     */
    public RetroActionController(final RetroActionService actionService) {
        this.actionService = actionService;
    }

    /**
     * Creates a new action while the session is in {@link
     * fr.pivot.agilite.retro.session.RetroPhase#ACTION} — callable by the facilitator or any other
     * member of the session's team.
     *
     * @param id        the session UUID from the path
     * @param request   the creation request — title, and optional ownerUserId/dueDate/sourceCardId
     * @param principal the resolved caller identity (user + tenant)
     * @return the created action with HTTP 201 Created
     */
    @PostMapping("/retro/sessions/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    public RetroActionResponse create(
            @PathVariable final UUID id,
            @RequestBody @Valid final CreateRetroActionRequest request,
            final RequestPrincipal principal) {
        return actionService.create(id, request, principal.userId(), principal.tenantId());
    }

    /**
     * Changes an existing action's status — free transitions between all 4 statuses.
     *
     * @param actionId  the action UUID from the path
     * @param request   the status update request
     * @param principal the resolved caller identity
     * @return the updated action
     */
    @PatchMapping("/retro/actions/{actionId}")
    public RetroActionResponse updateStatus(
            @PathVariable final UUID actionId,
            @RequestBody @Valid final UpdateRetroActionStatusRequest request,
            final RequestPrincipal principal) {
        return actionService.updateStatus(actionId, request.status(), principal.userId(), principal.tenantId());
    }

    /**
     * Lists every action belonging to a team, across every session (including sessions already
     * {@link fr.pivot.agilite.retro.session.RetroPhase#CLOSED}), optionally filtered/sorted.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param status    optional status filter (one of {@code A_FAIRE}/{@code EN_COURS}/
     *                  {@code TERMINEE}/{@code ABANDONNEE})
     * @param sort      optional sort key — {@code "status"} or {@code "dueDate"}
     * @param principal the resolved caller identity
     * @return the team's actions, optionally filtered/sorted
     */
    @GetMapping("/retro/teams/{teamId}/actions")
    public List<RetroActionResponse> list(
            @PathVariable final Long teamId,
            @RequestParam(required = false) final String status,
            @RequestParam(required = false) final String sort,
            final RequestPrincipal principal) {
        return actionService.listForTeam(teamId, status, sort, principal.userId(), principal.tenantId());
    }
}
