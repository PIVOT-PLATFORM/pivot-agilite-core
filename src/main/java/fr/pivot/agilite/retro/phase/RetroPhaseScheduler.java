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
 * AC: "when le timer configuré expire, then la phase passe automatiquement à REVUE"). Also
 * covers the equivalent {@code VOTE} → {@code ACTION} (US20.1.2b, {@link #checkVoteTimers()})
 * and {@code ACTION} → {@code CLOSED} (US20.1.2c, {@link #checkActionTimers()}) timers.
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

    /**
     * Scans every {@code VOTE}-phase session and auto-transitions those whose configured {@code
     * voteTimerSeconds} has elapsed since the session entered {@code VOTE} (US20.1.2b AC: "when
     * le timer configuré expire ... then la phase passe à ACTION").
     *
     * <p><strong>{@code updatedAt}, not {@code createdAt}, is the "VOTE phase started at"
     * marker.</strong> Unlike {@link #checkContributionTimers()} — where a session's {@code
     * createdAt} already *is* the start of its first phase — {@code VOTE} is not the session's
     * initial phase, so its start time is not a fixed, immutable column. Instead this relies on
     * the invariant, already true today, that a {@link RetroSession} row's {@code updatedAt} is
     * only ever refreshed by a phase transition ({@code RetroPhaseService#transitionTo}/{@code
     * transitionToActionWithRanking} calling {@code session.setCurrentPhase(...)} then {@code
     * save(...)}, which triggers the entity's {@code @PreUpdate}). Entering {@code VOTE} via
     * {@link RetroPhaseService#openVote} is itself such a transition, so it naturally stamps
     * {@code updatedAt} with exactly the timestamp this scheduler needs — no dedicated "phase
     * started at" column required, mirroring {@code checkContributionTimers()}'s own rationale for
     * {@code createdAt}/{@code CONTRIBUTION}.
     *
     * <p>Sessions with no configured {@code voteTimerSeconds} ({@code null} — manual closure
     * only) are skipped entirely, never auto-transitioned.
     */
    @Scheduled(fixedDelayString = "${pivot.agilite.retro.phase-scheduler.fixed-delay-ms:2000}")
    public void checkVoteTimers() {
        Instant now = clock.instant();
        for (RetroSession session : sessionRepository.findByCurrentPhase(RetroPhase.VOTE)) {
            Integer timerSeconds = session.getVoteTimerSeconds();
            if (timerSeconds == null) {
                continue;
            }
            Instant deadline = session.getUpdatedAt().plusSeconds(timerSeconds);
            if (!deadline.isAfter(now)) {
                LOG.debug("Vote timer elapsed for session={}, auto-transitioning to ACTION", session.getId());
                phaseService.autoTransitionToAction(session.getId());
            }
        }
    }

    /**
     * Scans every {@code ACTION}-phase session and auto-transitions those whose configured
     * {@code actionTimerSeconds} has elapsed since the session entered {@code ACTION} (US20.1.2c
     * AC: "when le timer configuré expire ... then un événement SESSION_CLOSED est diffusé et la
     * session passe en lecture seule").
     *
     * <p>Same {@code updatedAt}-as-phase-start-marker rationale as {@link #checkVoteTimers()}:
     * entering {@code ACTION} via {@link RetroPhaseService#closeVote}/{@code
     * autoTransitionToAction} is itself a phase transition, so it naturally stamps {@code
     * updatedAt} with exactly the timestamp this scheduler needs — no dedicated "phase started
     * at" column required.
     *
     * <p>Sessions with no configured {@code actionTimerSeconds} ({@code null} — manual closure
     * only) are skipped entirely, never auto-closed.
     */
    @Scheduled(fixedDelayString = "${pivot.agilite.retro.phase-scheduler.fixed-delay-ms:2000}")
    public void checkActionTimers() {
        Instant now = clock.instant();
        for (RetroSession session : sessionRepository.findByCurrentPhase(RetroPhase.ACTION)) {
            Integer timerSeconds = session.getActionTimerSeconds();
            if (timerSeconds == null) {
                continue;
            }
            Instant deadline = session.getUpdatedAt().plusSeconds(timerSeconds);
            if (!deadline.isAfter(now)) {
                LOG.debug("Action timer elapsed for session={}, auto-closing session", session.getId());
                phaseService.autoTransitionToClose(session.getId());
            }
        }
    }
}
