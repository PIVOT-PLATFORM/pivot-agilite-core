package fr.pivot.agilite.retro.action;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence access for {@link RetroAction} (US20.3.1).
 */
public interface RetroActionRepository extends JpaRepository<RetroAction, UUID> {

    /**
     * Finds every action belonging to a team, oldest first, regardless of which session created
     * them or that session's current phase — including sessions already {@link
     * fr.pivot.agilite.retro.session.RetroPhase#CLOSED} (US20.3.1 AC: a team's action list stays
     * accessible independent of its originating session's lifecycle).
     *
     * @param teamId the owning team's {@code public.teams.id}
     * @return the team's actions in creation order
     */
    List<RetroAction> findByTeamIdOrderByCreatedAtAsc(Long teamId);
}
