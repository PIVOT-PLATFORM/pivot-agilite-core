package fr.pivot.agilite.retro.session;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.retro.session.dto.CreateRetroSessionRequest;
import fr.pivot.agilite.retro.session.dto.RetroSessionJoinResponse;
import fr.pivot.agilite.retro.session.dto.RetroSessionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing retrospective session operations under {@code /retro/sessions}
 * (US20.1.1).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/retro/sessions}.
 * {@link #create} and {@link #findById} require a valid {@code Authorization: Bearer <token>}
 * header, resolved into a {@link RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3).
 * {@link #findByJoinCode} is deliberately public — see its JavaDoc.
 */
@RestController
@RequestMapping("/retro/sessions")
@Validated
public class RetroSessionController {

    private final RetroSessionService sessionService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param sessionService the retro session business logic service
     */
    public RetroSessionController(final RetroSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Creates a new retro session. The caller is automatically assigned as facilitator.
     *
     * @param request   the creation request — title, format, teamId, and optional
     *                  sprintRef/timers/vote count
     * @param principal the resolved caller identity (user + tenant)
     * @return the created session with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RetroSessionResponse create(
            @RequestBody @Valid final CreateRetroSessionRequest request,
            final RequestPrincipal principal) {
        return sessionService.create(request, principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single session's full detail, for any tenant-matching authenticated caller,
     * regardless of the session's current phase (including {@link RetroPhase#CLOSED}).
     *
     * @param id        the session UUID from the path
     * @param principal the resolved caller identity
     * @return the full session detail, or HTTP 404 if not found or owned by another tenant
     */
    @GetMapping("/{id}")
    public RetroSessionResponse findById(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        return sessionService.findByIdForTenant(id, principal.tenantId());
    }

    /**
     * Resolves a join code to the minimal public session metadata, without requiring any
     * authentication — deliberate design choice so that joining a retrospective by code stays
     * frictionless (EPIC README explicitly calls out Retrium's paywalled/friction-heavy join
     * flow as the anti-pattern to avoid).
     *
     * @param joinCode the 6-character alphanumeric join code from the path
     * @return the minimal join metadata, HTTP 404 if unknown, HTTP 410 if expired or closed
     */
    @GetMapping("/join/{joinCode}")
    public RetroSessionJoinResponse findByJoinCode(@PathVariable final String joinCode) {
        return sessionService.findByJoinCode(joinCode);
    }
}
