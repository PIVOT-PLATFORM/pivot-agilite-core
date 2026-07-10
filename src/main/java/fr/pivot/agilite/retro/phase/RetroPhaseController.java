package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.retro.phase.dto.RevealResponse;
import fr.pivot.agilite.retro.session.RetroPhase;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller exposing facilitator-triggered retro session phase actions under
 * {@code /retro/sessions/{id}} (US20.1.2a).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/retro/sessions/{id}}.
 * Both endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} exactly like {@code RetroSessionController} — unlike card submission
 * (STOMP, open to account-less participants), only the session's facilitator may ever trigger
 * these actions, so there is no anonymous path to support here.
 */
@RestController
@RequestMapping("/retro/sessions/{id}")
public class RetroPhaseController {

    private final RetroPhaseService phaseService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param phaseService the phase-transition/reveal business logic service
     */
    public RetroPhaseController(final RetroPhaseService phaseService) {
        this.phaseService = phaseService;
    }

    /**
     * Manually closes the contribution phase, immediately transitioning to {@link
     * RetroPhase#REVUE} before any configured timer would have expired it.
     *
     * @param id        the session UUID from the path
     * @param principal the resolved caller identity (must be the session's facilitator)
     * @return the session's new phase
     */
    @PostMapping("/contribution/close")
    public Map<String, RetroPhase> closeContribution(
            @PathVariable final UUID id, final RequestPrincipal principal) {
        RetroPhase newPhase = phaseService.closeContribution(id, principal.userId(), principal.tenantId());
        return Map.of("currentPhase", newPhase);
    }

    /**
     * Triggers the reveal: every submitted card is broadcast in clear, grouped by column, to
     * every participant.
     *
     * @param id        the session UUID from the path
     * @param principal the resolved caller identity (must be the session's facilitator)
     * @return the revealed cards, grouped by column
     */
    @PostMapping("/reveal")
    public RevealResponse reveal(@PathVariable final UUID id, final RequestPrincipal principal) {
        return phaseService.reveal(id, principal.userId(), principal.tenantId());
    }
}
