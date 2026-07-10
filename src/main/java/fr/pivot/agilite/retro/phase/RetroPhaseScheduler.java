package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Periodically checks every session still in {@link RetroPhase#CONTRIBUTION} for an elapsed
 * configured timer, triggering the automatic transition to {@link RetroPhase#REVUE} (US20.1.2a
 * AC: "when le timer configuré expire, then la phase passe automatiquement à REVUE").
 *
 * <p><strong>No dedicated "phase started at" column needed.</strong> {@code
 * contributionTimerSeconds} is measured from the session's {@code createdAt} — a session is
 * always created directly into {@link RetroPhase#CONTRIBUTION} (see {@code RetroSession}'s
 * constructor), so its creation timestamp already *is* the start of that phase. Once a session
 * leaves {@code CONTRIBUTION} (auto or manual), this scheduler simply stops selecting it — no
 * extra bookkeeping required.
 *
 * <p>Sessions with no configured {@code contributionTimerSeconds} ({@code null} — manual closure
 * only) are skipped entirely, never auto-transitioned.
 */
@Component
public class RetroPhaseScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(RetroPhaseScheduler.class);

    private final RetroSessionRepository sessionRepository;
    private final RetroPhaseService phaseService;
    private final Clock clock;

    /**
     * Constructs the scheduler with its required dependencies.
     *
     * @param sessionRepository retro session persistence, used to find CONTRIBUTION-phase
     *                          candidates
     * @param phaseService      performs the actual transition and broadcast
     * @param clock             the shared clock, overridable in tests
     */
    public RetroPhaseScheduler(
            final RetroSessionRepository sessionRepository,
            final RetroPhaseService phaseService,
            final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.phaseService = phaseService;
        this.clock = clock;
    }

    /**
     * Scans every {@code CONTRIBUTION}-phase session and auto-transitions those whose configured
     * timer has elapsed since creation.
     */
    @Scheduled(fixedDelayString = "${pivot.agilite.retro.phase-scheduler.fixed-delay-ms:2000}")
    public void checkContributionTimers() {
        Instant now = clock.instant();
        for (RetroSession session : sessionRepository.findByCurrentPhase(RetroPhase.CONTRIBUTION)) {
            Integer timerSeconds = session.getContributionTimerSeconds();
            if (timerSeconds == null) {
                continue;
            }
            Instant deadline = session.getCreatedAt().plusSeconds(timerSeconds);
            if (!deadline.isAfter(now)) {
                LOG.debug("Contribution timer elapsed for session={}, auto-transitioning to REVUE",
                        session.getId());
                phaseService.autoTransitionToRevue(session.getId());
            }
        }
    }
}
