package fr.pivot.agilite.wheel;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.wheel.dto.CreateWheelRequest;
import fr.pivot.agilite.wheel.dto.UpdateWheelRequest;
import fr.pivot.agilite.wheel.dto.WheelDrawResponse;
import fr.pivot.agilite.wheel.dto.WheelResponse;
import fr.pivot.agilite.wheel.dto.WheelSpinRequest;
import fr.pivot.agilite.wheel.dto.WheelSpinResponse;
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
 * REST controller exposing wheel operations under {@code /wheels} (US14.1.1, extended by
 * US14.2.1 with the weighted anti-repeat draw).
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
    private final WheelDrawService wheelDrawService;

    /**
     * Creates the controller with its required service dependencies.
     *
     * @param wheelService     the wheel CRUD business logic service (US14.1.1)
     * @param wheelDrawService the weighted anti-repeat draw business logic service (US14.2.1)
     */
    public WheelController(final WheelService wheelService, final WheelDrawService wheelDrawService) {
        this.wheelService = wheelService;
        this.wheelDrawService = wheelDrawService;
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

    /**
     * Performs a weighted, anti-repeat draw on a wheel (US14.2.1).
     *
     * @param wheelId   the wheel UUID from the path
     * @param request   the spin request — optional {@code antiRepeatMode}; the body itself may be
     *                  entirely omitted, in which case {@code reduced_weight} is used
     * @param principal the resolved caller identity
     * @return the draw result with HTTP 201 Created
     */
    @PostMapping("/{wheelId}/spin")
    @ResponseStatus(HttpStatus.CREATED)
    public WheelSpinResponse spin(
            @PathVariable final UUID wheelId,
            @RequestBody(required = false) final WheelSpinRequest request,
            final RequestPrincipal principal) {
        String rawAntiRepeatMode = request != null ? request.antiRepeatMode() : null;
        return wheelDrawService.spin(wheelId, rawAntiRepeatMode, principal.userId(), principal.tenantId());
    }

    /**
     * Lists the most recent draws of a wheel, most recent first (US14.2.1).
     *
     * @param wheelId   the wheel UUID from the path
     * @param limit     the maximum number of draws to return (1-100, default 20)
     * @param principal the resolved caller identity
     * @return the most recent draws, most recent first
     */
    @GetMapping("/{wheelId}/draws")
    public List<WheelDrawResponse> draws(
            @PathVariable final UUID wheelId,
            @RequestParam(required = false) final String limit,
            final RequestPrincipal principal) {
        return wheelDrawService.listDraws(wheelId, limit, principal.userId(), principal.tenantId());
    }
}
