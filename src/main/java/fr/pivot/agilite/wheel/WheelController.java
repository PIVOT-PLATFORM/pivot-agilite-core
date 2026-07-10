package fr.pivot.agilite.wheel;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.wheel.dto.CreateWheelRequest;
import fr.pivot.agilite.wheel.dto.UpdateWheelRequest;
import fr.pivot.agilite.wheel.dto.WheelResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing wheel operations under {@code /wheels} (US14.1.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into
 * a {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 * Missing, malformed, or rejected tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/wheels}.
 */
@RestController
@RequestMapping("/wheels")
@Validated
public class WheelController {

    private final WheelService wheelService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param wheelService the wheel business logic service
     */
    public WheelController(final WheelService wheelService) {
        this.wheelService = wheelService;
    }

    /**
     * Creates a new wheel with its entries.
     *
     * @param request   the wheel creation request — team id, name (1-100 chars), and at least
     *                  one entry (team-member reference or free text, weight 1-10)
     * @param principal the resolved caller identity (user + tenant)
     * @return the created wheel with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WheelResponse create(
            @RequestBody @Valid final CreateWheelRequest request,
            final RequestPrincipal principal) {
        return wheelService.create(
                request.teamId(), request.name(), request.entries(), principal.userId(), principal.tenantId());
    }

    /**
     * Lists all wheels belonging to a team.
     *
     * @param teamId    the team's {@code public.teams.id}
     * @param principal the resolved caller identity
     * @return the team's wheels (no pagination — small expected volume per team)
     */
    @GetMapping
    public List<WheelResponse> list(
            @RequestParam @NotNull final Long teamId,
            final RequestPrincipal principal) {
        return wheelService.findAllForTeam(teamId, principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single wheel by its identifier, if the caller has access.
     *
     * @param wheelId   the wheel UUID from the path
     * @param principal the resolved caller identity
     * @return the wheel, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{wheelId}")
    public WheelResponse findById(
            @PathVariable final UUID wheelId,
            final RequestPrincipal principal) {
        return wheelService.findById(wheelId, principal.userId(), principal.tenantId());
    }

    /**
     * Fully replaces a wheel's name and entries.
     *
     * @param wheelId   the wheel UUID from the path
     * @param request   the update request — new name and full entries list (at least one entry)
     * @param principal the resolved caller identity
     * @return the updated wheel response
     */
    @PutMapping("/{wheelId}")
    public WheelResponse update(
            @PathVariable final UUID wheelId,
            @RequestBody @Valid final UpdateWheelRequest request,
            final RequestPrincipal principal) {
        return wheelService.update(
                wheelId, request.name(), request.entries(), principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a wheel and all its entries.
     *
     * @param wheelId   the wheel UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{wheelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID wheelId,
            final RequestPrincipal principal) {
        wheelService.delete(wheelId, principal.userId(), principal.tenantId());
    }
}
