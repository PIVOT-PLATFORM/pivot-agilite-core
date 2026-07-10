package fr.pivot.agilite.retro.action;

import fr.pivot.agilite.exception.InvalidRetroActionStatusException;
import fr.pivot.agilite.exception.RetroActionNotFoundException;
import fr.pivot.agilite.exception.RetroActionOwnerNotTeamMemberException;
import fr.pivot.agilite.exception.RetroActionSourceCardMismatchException;
import fr.pivot.agilite.exception.RetroInvalidPhaseTransitionException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.exception.TeamNotFoundException;
import fr.pivot.agilite.retro.action.dto.ActionCreatedEvent;
import fr.pivot.agilite.retro.action.dto.CreateRetroActionRequest;
import fr.pivot.agilite.retro.action.dto.RetroActionResponse;
import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import fr.pivot.core.team.Team;
import fr.pivot.core.team.TeamMemberRepository;
import fr.pivot.core.team.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for creating, updating, and listing retrospective actions (US20.3.1).
 *
 * <p>Actions are created by any member of the session's team (not just the facilitator) while the
 * session is in {@link RetroPhase#ACTION}. Team existence/tenant-ownership/caller-membership is
 * validated directly against {@code fr.pivot.core.team.TeamRepository}/{@code
 * TeamMemberRepository}, exported as-is by {@code pivot-core-starter} — same convention as {@code
 * RetroSessionService}, not the locally-duplicated {@code Platform*} entities used by the wheel
 * package.
 *
 * <p><strong>Anti-enumeration posture.</strong> Every access-control rejection in this service —
 * cross-tenant, non-existent, and "exists but caller is not a team member" — collapses to the
 * same 404 ({@link RetroSessionNotFoundException}, {@link RetroActionNotFoundException}, or
 * {@link TeamNotFoundException} depending on the resource in the path), never a 403. This is a
 * deliberate US20.3.1 AC ("jamais 403 — pas de confirmation cross-tenant"), stricter than {@code
 * RetroSessionService#create}'s own 403 ({@code RetroTeamAccessDeniedException}) for a team that
 * genuinely exists in the caller's tenant.
 */
@Service
public class RetroActionService {

    private static final Logger LOG = LoggerFactory.getLogger(RetroActionService.class);

    private final RetroActionRepository actionRepository;
    private final RetroSessionRepository sessionRepository;
    private final RetroCardRepository cardRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param actionRepository    retro action persistence
     * @param sessionRepository   retro session persistence, used to resolve/validate the owning
     *                            session on creation
     * @param cardRepository      card persistence, used to validate an optional {@code
     *                            sourceCardId} belongs to the target session
     * @param teamRepository      {@code pivot-core-starter}'s team persistence
     * @param teamMemberRepository {@code pivot-core-starter}'s team membership persistence
     * @param messagingTemplate   used to broadcast {@code ACTION_CREATED}
     */
    public RetroActionService(
            final RetroActionRepository actionRepository,
            final RetroSessionRepository sessionRepository,
            final RetroCardRepository cardRepository,
            final TeamRepository teamRepository,
            final TeamMemberRepository teamMemberRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.actionRepository = actionRepository;
        this.sessionRepository = sessionRepository;
        this.cardRepository = cardRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Creates a new retro action while its session is in {@link RetroPhase#ACTION}, broadcasting
     * {@code ACTION_CREATED} on the session's topic.
     *
     * @param sessionId the owning session's id, from the path
     * @param request   the validated creation request
     * @param callerId  the authenticated caller's {@code public.users.id} — must be a member of
     *                  the session's team (facilitator or any other member alike)
     * @param tenantId  the authenticated caller's {@code public.tenants.id}, extracted exclusively
     *                  from the resolved bearer token — never from the request body
     * @return the created action, HTTP 201
     * @throws RetroSessionNotFoundException              if the session does not exist, belongs
     *                                                     to a different tenant, or the caller is
     *                                                     not a member of its team (never 403)
     * @throws RetroInvalidPhaseTransitionException       if the session is not currently in
     *                                                     {@link RetroPhase#ACTION}
     * @throws RetroActionOwnerNotTeamMemberException     if {@code ownerUserId} is supplied but
     *                                                     does not resolve to a member of the
     *                                                     session's team
     * @throws RetroActionSourceCardMismatchException     if {@code sourceCardId} is supplied but
     *                                                     does not resolve to a card belonging to
     *                                                     this session
     */
    @Transactional
    public RetroActionResponse create(
            final UUID sessionId, final CreateRetroActionRequest request,
            final Long callerId, final Long tenantId) {
        RetroSession session = loadSessionForTeamMember(sessionId, callerId, tenantId);
        if (session.getCurrentPhase() != RetroPhase.ACTION) {
            throw new RetroInvalidPhaseTransitionException(sessionId, RetroPhase.ACTION, session.getCurrentPhase());
        }
        validateOwner(session.getTeamId(), request.ownerUserId());
        validateSourceCard(sessionId, request.sourceCardId());

        Instant now = Instant.now();
        RetroAction action = new RetroAction(
                tenantId, session.getTeamId(), sessionId, request.sourceCardId(),
                request.title(), request.ownerUserId(), request.dueDate(), callerId, now);
        RetroAction saved = actionRepository.save(action);

        LOG.info("Retro action created: session={} action={} team={}", sessionId, saved.getId(), session.getTeamId());
        messagingTemplate.convertAndSend(
                RetroSessionDestinations.roomTopic(sessionId),
                (Object) ActionCreatedEvent.of(saved));
        return RetroActionResponse.from(saved);
    }

    /**
     * Changes an existing action's status — free transitions between all 4 statuses, no strict
     * state machine (an {@link RetroActionStatus#ABANDONNEE} action may be reopened).
     *
     * @param actionId   the action to update
     * @param rawStatus  the raw status value from the request body
     * @param callerId   the authenticated caller's {@code public.users.id} — must be a member of
     *                   the action's team
     * @param tenantId   the authenticated caller's {@code public.tenants.id}
     * @return the updated action
     * @throws RetroActionNotFoundException      if the action does not exist, belongs to a
     *                                            different tenant, or the caller is not a member
     *                                            of its team (never 403)
     * @throws InvalidRetroActionStatusException if {@code rawStatus} does not match any {@link
     *                                            RetroActionStatus} constant
     */
    @Transactional
    public RetroActionResponse updateStatus(
            final UUID actionId, final String rawStatus, final Long callerId, final Long tenantId) {
        RetroAction action = loadActionForTeamMember(actionId, callerId, tenantId);
        RetroActionStatus newStatus = parseStatus(rawStatus);
        action.setStatus(newStatus);
        RetroAction saved = actionRepository.save(action);
        LOG.info("Retro action status changed: action={} status={}", actionId, newStatus);
        return RetroActionResponse.from(saved);
    }

    /**
     * Lists every action belonging to a team, across every session (past and present, including
     * sessions already {@link RetroPhase#CLOSED}), optionally filtered by status and sorted.
     *
     * @param teamId    the team's {@code public.teams.id}, from the path
     * @param rawStatus optional status filter, or {@code null}/blank for no filter
     * @param sort      optional sort key — {@code "status"} or {@code "dueDate"}; any other value
     *                  (including {@code null}) keeps the default creation-order
     * @param callerId  the authenticated caller's {@code public.users.id} — must be a member of
     *                  {@code teamId}
     * @param tenantId  the authenticated caller's {@code public.tenants.id}
     * @return the team's actions, optionally filtered/sorted
     * @throws TeamNotFoundException             if the team does not exist, belongs to a
     *                                            different tenant, or the caller is not one of its
     *                                            members (never 403)
     * @throws InvalidRetroActionStatusException if {@code rawStatus} is supplied but does not
     *                                            match any {@link RetroActionStatus} constant
     */
    @Transactional(readOnly = true)
    public List<RetroActionResponse> listForTeam(
            final Long teamId, final String rawStatus, final String sort,
            final Long callerId, final Long tenantId) {
        requireTeamMember(teamId, callerId, tenantId);
        RetroActionStatus statusFilter = rawStatus == null || rawStatus.isBlank() ? null : parseStatus(rawStatus);

        List<RetroAction> actions = actionRepository.findByTeamIdOrderByCreatedAtAsc(teamId);
        List<RetroAction> filtered = statusFilter == null
                ? actions
                : actions.stream().filter(action -> action.getStatus() == statusFilter).toList();
        List<RetroAction> sorted = resolveComparator(sort)
                .map(comparator -> filtered.stream().sorted(comparator).toList())
                .orElse(filtered);
        return sorted.stream().map(RetroActionResponse::from).toList();
    }

    /**
     * Resolves a session, scoped to the caller's tenant, then verifies the caller is a member of
     * its team.
     *
     * @param sessionId the session id
     * @param callerId  the caller's user id
     * @param tenantId  the caller's tenant id
     * @return the matching session
     * @throws RetroSessionNotFoundException if not found, owned by a different tenant, or the
     *                                        caller is not a member of its team
     */
    private RetroSession loadSessionForTeamMember(final UUID sessionId, final Long callerId, final Long tenantId) {
        RetroSession session = sessionRepository.findById(sessionId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new RetroSessionNotFoundException(sessionId));
        teamMemberRepository.findByTeamIdAndUserId(session.getTeamId(), callerId)
                .orElseThrow(() -> new RetroSessionNotFoundException(sessionId));
        return session;
    }

    /**
     * Resolves an action, scoped to the caller's tenant, then verifies the caller is a member of
     * its team.
     *
     * @param actionId the action id
     * @param callerId the caller's user id
     * @param tenantId the caller's tenant id
     * @return the matching action
     * @throws RetroActionNotFoundException if not found, owned by a different tenant, or the
     *                                       caller is not a member of its team
     */
    private RetroAction loadActionForTeamMember(final UUID actionId, final Long callerId, final Long tenantId) {
        RetroAction action = actionRepository.findById(actionId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new RetroActionNotFoundException(actionId));
        teamMemberRepository.findByTeamIdAndUserId(action.getTeamId(), callerId)
                .orElseThrow(() -> new RetroActionNotFoundException(actionId));
        return action;
    }

    /**
     * Verifies a team exists in the caller's tenant and the caller is one of its members.
     *
     * @param teamId   the team id
     * @param callerId the caller's user id
     * @param tenantId the caller's tenant id
     * @throws TeamNotFoundException if the team does not exist, belongs to a different tenant, or
     *                                the caller is not one of its members
     */
    private void requireTeamMember(final Long teamId, final Long callerId, final Long tenantId) {
        Team team = teamRepository.findById(teamId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new TeamNotFoundException(teamId));
        teamMemberRepository.findByTeamIdAndUserId(team.getId(), callerId)
                .orElseThrow(() -> new TeamNotFoundException(teamId));
    }

    /**
     * Validates an optional {@code ownerUserId} resolves to a member of the session's team.
     *
     * @param teamId      the session's team id
     * @param ownerUserId the requested owner id, or {@code null} (always valid)
     * @throws RetroActionOwnerNotTeamMemberException if {@code ownerUserId} is not a member of
     *                                                 {@code teamId}
     */
    private void validateOwner(final Long teamId, final Long ownerUserId) {
        if (ownerUserId == null) {
            return;
        }
        teamMemberRepository.findByTeamIdAndUserId(teamId, ownerUserId)
                .orElseThrow(() -> new RetroActionOwnerNotTeamMemberException(ownerUserId));
    }

    /**
     * Validates an optional {@code sourceCardId} resolves to a card belonging to {@code
     * sessionId}.
     *
     * @param sessionId    the target session id
     * @param sourceCardId the requested source card id, or {@code null} (always valid)
     * @throws RetroActionSourceCardMismatchException if {@code sourceCardId} does not resolve to a
     *                                                 card belonging to {@code sessionId}
     */
    private void validateSourceCard(final UUID sessionId, final UUID sourceCardId) {
        if (sourceCardId == null) {
            return;
        }
        RetroCard card = cardRepository.findById(sourceCardId)
                .orElseThrow(() -> new RetroActionSourceCardMismatchException(sourceCardId));
        if (!card.getSessionId().equals(sessionId)) {
            throw new RetroActionSourceCardMismatchException(sourceCardId);
        }
    }

    /**
     * Parses a raw status string against the {@link RetroActionStatus} catalogue.
     *
     * @param rawStatus the raw status string
     * @return the matching {@link RetroActionStatus} constant
     * @throws InvalidRetroActionStatusException if {@code rawStatus} does not match any constant
     */
    private static RetroActionStatus parseStatus(final String rawStatus) {
        try {
            return RetroActionStatus.valueOf(rawStatus);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRetroActionStatusException(rawStatus);
        }
    }

    /**
     * Resolves the sort comparator for the {@code sort} query parameter.
     *
     * @param sort {@code "status"}, {@code "dueDate"}, or anything else (including {@code null})
     * @return the comparator to apply, or empty to keep the default creation-order (already the
     *         order {@link RetroActionRepository#findByTeamIdOrderByCreatedAtAsc} returns)
     */
    private static Optional<Comparator<RetroAction>> resolveComparator(final String sort) {
        if ("status".equalsIgnoreCase(sort)) {
            return Optional.of(Comparator.comparing(RetroAction::getStatus)
                    .thenComparing(RetroAction::getCreatedAt));
        }
        if ("dueDate".equalsIgnoreCase(sort)) {
            return Optional.of(Comparator.comparing(
                            RetroAction::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RetroAction::getCreatedAt));
        }
        return Optional.empty();
    }
}
