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
import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import fr.pivot.core.team.Team;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import fr.pivot.core.team.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroActionService} covering all business branches (US20.3.1/US20.3.2):
 * creation gating (team membership, ACTION phase, owner/source-card validation), free status
 * transitions, team-scoped listing (filter/sort), and team-scoped pending-actions listing, all
 * with mocked dependencies — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class RetroActionServiceTest {

    private static final Long TENANT_A = 100L;
    private static final Long TENANT_B = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long FACILITATOR_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long NON_MEMBER_ID = 3L;

    @Mock
    private RetroActionRepository actionRepository;

    @Mock
    private RetroSessionRepository sessionRepository;

    @Mock
    private RetroCardRepository cardRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RetroActionService service;

    @BeforeEach
    void setUp() {
        service = new RetroActionService(
                actionRepository, sessionRepository, cardRepository, teamRepository,
                teamMemberRepository, messagingTemplate);
        lenient().when(actionRepository.save(any(RetroAction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    /**
     * Given a session in ACTION phase and the caller is a team member (not the facilitator),
     * when create() is called with only a title, then a A_FAIRE action is persisted, linked to
     * the session and its team, and ACTION_CREATED is broadcast.
     */
    @Test
    void create_asTeamMemberInActionPhase_persistsAndBroadcasts() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        CreateRetroActionRequest request = new CreateRetroActionRequest("Automate deploys", null, null, null);
        RetroActionResponse response = service.create(session.getId(), request, MEMBER_ID, TENANT_A);

        assertThat(response.title()).isEqualTo("Automate deploys");
        assertThat(response.status()).isEqualTo("A_FAIRE");
        assertThat(response.sessionId()).isEqualTo(session.getId());
        assertThat(response.teamId()).isEqualTo(TEAM_ID);
        assertThat(response.ownerUserId()).isNull();
        assertThat(response.sourceCardId()).isNull();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        ActionCreatedEvent event = (ActionCreatedEvent) captor.getValue();
        assertThat(event.type()).isEqualTo(ActionCreatedEvent.TYPE);
        assertThat(event.title()).isEqualTo("Automate deploys");
        assertThat(event.status()).isEqualTo("A_FAIRE");
    }

    /**
     * Given a valid session, when create() is called with an ownerUserId that is a member of the
     * session's team, then the action persists with that owner.
     */
    @Test
    void create_withOwnerUserIdThatIsTeamMember_persistsOwner() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        CreateRetroActionRequest request = new CreateRetroActionRequest(
                "Fix flaky test", MEMBER_ID, LocalDate.of(2026, 8, 1), null);
        RetroActionResponse response = service.create(session.getId(), request, FACILITATOR_ID, TENANT_A);

        assertThat(response.ownerUserId()).isEqualTo(MEMBER_ID);
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    /**
     * Given a valid session, when create() is called with an ownerUserId that is not a member of
     * the session's team, then it throws {@link RetroActionOwnerNotTeamMemberException} (400).
     */
    @Test
    void create_withOwnerUserIdNotTeamMember_throwsOwnerNotTeamMemberException() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, NON_MEMBER_ID))
                .thenReturn(Optional.empty());

        CreateRetroActionRequest request = new CreateRetroActionRequest("Task", NON_MEMBER_ID, null, null);

        assertThatThrownBy(() -> service.create(session.getId(), request, FACILITATOR_ID, TENANT_A))
                .isInstanceOf(RetroActionOwnerNotTeamMemberException.class);
        verify(actionRepository, never()).save(any());
    }

    /**
     * Given a valid session with a card belonging to it, when create() is called with that card
     * as {@code sourceCardId}, then the action persists linked to it.
     */
    @Test
    void create_withSourceCardBelongingToSession_persistsSourceCard() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));
        RetroCard card = cardWithId(UUID.randomUUID(), session.getId());
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        CreateRetroActionRequest request = new CreateRetroActionRequest("From card", null, null, card.getId());
        RetroActionResponse response = service.create(session.getId(), request, FACILITATOR_ID, TENANT_A);

        assertThat(response.sourceCardId()).isEqualTo(card.getId());
    }

    /**
     * Given a valid session, when create() is called with a {@code sourceCardId} that does not
     * exist at all, then it throws {@link RetroActionSourceCardMismatchException} (400).
     */
    @Test
    void create_withUnknownSourceCardId_throwsSourceCardMismatchException() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));
        UUID unknownCardId = UUID.randomUUID();
        when(cardRepository.findById(unknownCardId)).thenReturn(Optional.empty());

        CreateRetroActionRequest request = new CreateRetroActionRequest("From card", null, null, unknownCardId);

        assertThatThrownBy(() -> service.create(session.getId(), request, FACILITATOR_ID, TENANT_A))
                .isInstanceOf(RetroActionSourceCardMismatchException.class);
        verify(actionRepository, never()).save(any());
    }

    /**
     * Given a valid session, when create() is called with a {@code sourceCardId} belonging to a
     * different session, then it throws {@link RetroActionSourceCardMismatchException} (400).
     */
    @Test
    void create_withSourceCardFromDifferentSession_throwsSourceCardMismatchException() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));
        RetroCard card = cardWithId(UUID.randomUUID(), UUID.randomUUID());
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        CreateRetroActionRequest request = new CreateRetroActionRequest("From card", null, null, card.getId());

        assertThatThrownBy(() -> service.create(session.getId(), request, FACILITATOR_ID, TENANT_A))
                .isInstanceOf(RetroActionSourceCardMismatchException.class);
        verify(actionRepository, never()).save(any());
    }

    /**
     * Given a session not currently in ACTION phase (CONTRIBUTION/REVUE/VOTE/CLOSED), when
     * create() is called, then it throws {@link RetroInvalidPhaseTransitionException} (409).
     */
    @Test
    void create_sessionNotInActionPhase_throwsInvalidPhaseTransition() {
        for (RetroPhase phase : List.of(RetroPhase.CONTRIBUTION, RetroPhase.REVUE, RetroPhase.VOTE, RetroPhase.CLOSED)) {
            RetroSession session = session(TENANT_A, TEAM_ID, phase);
            when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
            when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, FACILITATOR_ID))
                    .thenReturn(Optional.of(new TeamMember(TEAM_ID, FACILITATOR_ID)));

            CreateRetroActionRequest request = new CreateRetroActionRequest("Task", null, null, null);

            assertThatThrownBy(() -> service.create(session.getId(), request, FACILITATOR_ID, TENANT_A))
                    .isInstanceOf(RetroInvalidPhaseTransitionException.class);
        }
        verify(actionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Given an unknown session id, when create() is called, then it throws {@link
     * RetroSessionNotFoundException} (404).
     */
    @Test
    void create_unknownSession_throwsRetroSessionNotFoundException() {
        UUID unknown = UUID.randomUUID();
        when(sessionRepository.findById(unknown)).thenReturn(Optional.empty());

        CreateRetroActionRequest request = new CreateRetroActionRequest("Task", null, null, null);

        assertThatThrownBy(() -> service.create(unknown, request, FACILITATOR_ID, TENANT_A))
                .isInstanceOf(RetroSessionNotFoundException.class);
    }

    /**
     * Given a session belonging to a different tenant, when create() is called, then it throws
     * {@link RetroSessionNotFoundException} (404) — never confirming cross-tenant existence.
     */
    @Test
    void create_crossTenantSession_throwsRetroSessionNotFoundException() {
        RetroSession session = session(TENANT_B, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        CreateRetroActionRequest request = new CreateRetroActionRequest("Task", null, null, null);

        assertThatThrownBy(() -> service.create(session.getId(), request, FACILITATOR_ID, TENANT_A))
                .isInstanceOf(RetroSessionNotFoundException.class);
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    /**
     * Given a session that exists in the caller's own tenant, when create() is called by a caller
     * who is not a member of the session's team, then it throws {@link
     * RetroSessionNotFoundException} (404) — never a 403 (US20.3.1 AC).
     */
    @Test
    void create_callerNotTeamMember_throwsRetroSessionNotFoundException() {
        RetroSession session = session(TENANT_A, TEAM_ID, RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, NON_MEMBER_ID)).thenReturn(Optional.empty());

        CreateRetroActionRequest request = new CreateRetroActionRequest("Task", null, null, null);

        assertThatThrownBy(() -> service.create(session.getId(), request, NON_MEMBER_ID, TENANT_A))
                .isInstanceOf(RetroSessionNotFoundException.class);
        verify(actionRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateStatus()
    // -------------------------------------------------------------------------

    /**
     * Given an existing action and a team-member caller, when updateStatus() is called with a
     * valid status, then it persists the new status and does not broadcast anything.
     */
    @Test
    void updateStatus_asTeamMember_persistsNewStatus() {
        RetroAction action = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        RetroActionResponse response = service.updateStatus(action.getId(), "EN_COURS", MEMBER_ID, TENANT_A);

        assertThat(response.status()).isEqualTo("EN_COURS");
        assertThat(action.getStatus()).isEqualTo(RetroActionStatus.EN_COURS);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Given an ABANDONNEE action, when updateStatus() is called to reopen it to any other status,
     * then it succeeds — free transitions, no strict state machine (US20.3.1 AC).
     */
    @Test
    void updateStatus_reopensAbandonneeAction_succeeds() {
        RetroAction action = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        action.setStatus(RetroActionStatus.ABANDONNEE);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        RetroActionResponse response = service.updateStatus(action.getId(), "A_FAIRE", MEMBER_ID, TENANT_A);

        assertThat(response.status()).isEqualTo("A_FAIRE");
    }

    /**
     * Given an existing, accessible action, when updateStatus() is called with a value outside
     * the {@link RetroActionStatus} enumeration, then it throws {@link
     * InvalidRetroActionStatusException} (400).
     */
    @Test
    void updateStatus_invalidStatusValue_throwsInvalidRetroActionStatusException() {
        RetroAction action = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        assertThatThrownBy(() -> service.updateStatus(action.getId(), "NOT_A_STATUS", MEMBER_ID, TENANT_A))
                .isInstanceOf(InvalidRetroActionStatusException.class);
        verify(actionRepository, never()).save(any());
    }

    /**
     * Given an unknown action id, when updateStatus() is called, then it throws {@link
     * RetroActionNotFoundException} (404).
     */
    @Test
    void updateStatus_unknownAction_throwsRetroActionNotFoundException() {
        UUID unknown = UUID.randomUUID();
        when(actionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(unknown, "EN_COURS", MEMBER_ID, TENANT_A))
                .isInstanceOf(RetroActionNotFoundException.class);
    }

    /**
     * Given an action belonging to a different tenant, when updateStatus() is called, then it
     * throws {@link RetroActionNotFoundException} (404) — never confirming cross-tenant existence.
     */
    @Test
    void updateStatus_crossTenantAction_throwsRetroActionNotFoundException() {
        RetroAction action = actionWithId(UUID.randomUUID(), TENANT_B, TEAM_ID);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> service.updateStatus(action.getId(), "EN_COURS", MEMBER_ID, TENANT_A))
                .isInstanceOf(RetroActionNotFoundException.class);
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    /**
     * Given an action belonging to a team the caller is not a member of, when updateStatus() is
     * called, then it throws {@link RetroActionNotFoundException} (404) — never a 403.
     */
    @Test
    void updateStatus_callerNotTeamMember_throwsRetroActionNotFoundException() {
        RetroAction action = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        when(actionRepository.findById(action.getId())).thenReturn(Optional.of(action));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, NON_MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(action.getId(), "EN_COURS", NON_MEMBER_ID, TENANT_A))
                .isInstanceOf(RetroActionNotFoundException.class);
        verify(actionRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // listForTeam()
    // -------------------------------------------------------------------------

    /**
     * Given a team member caller and actions from multiple sessions (including one CLOSED), when
     * listForTeam() is called with no filter/sort, then every action is returned in creation
     * order.
     */
    @Test
    void listForTeam_noFilterOrSort_returnsAllInCreationOrder() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        RetroAction first = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        RetroAction second = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of(first, second));

        List<RetroActionResponse> result = service.listForTeam(TEAM_ID, null, null, MEMBER_ID, TENANT_A);

        assertThat(result).extracting(RetroActionResponse::id).containsExactly(first.getId(), second.getId());
    }

    /**
     * Given actions with differing statuses, when listForTeam() is called with a status filter,
     * then only matching actions are returned.
     */
    @Test
    void listForTeam_withStatusFilter_returnsOnlyMatching() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        RetroAction todo = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        RetroAction done = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        done.setStatus(RetroActionStatus.TERMINEE);
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of(todo, done));

        List<RetroActionResponse> result = service.listForTeam(TEAM_ID, "TERMINEE", null, MEMBER_ID, TENANT_A);

        assertThat(result).extracting(RetroActionResponse::id).containsExactly(done.getId());
    }

    /**
     * Given actions with differing due dates (one {@code null}), when listForTeam() is called
     * with {@code sort=dueDate}, then results are ordered ascending with the null due date last.
     */
    @Test
    void listForTeam_sortByDueDate_ordersAscendingWithNullsLast() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        RetroAction noDueDate = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        RetroAction laterDueDate = actionWithDueDate(UUID.randomUUID(), TENANT_A, TEAM_ID, LocalDate.of(2026, 9, 1));
        RetroAction earlierDueDate = actionWithDueDate(UUID.randomUUID(), TENANT_A, TEAM_ID, LocalDate.of(2026, 8, 1));
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID))
                .thenReturn(List.of(noDueDate, laterDueDate, earlierDueDate));

        List<RetroActionResponse> result = service.listForTeam(TEAM_ID, null, "dueDate", MEMBER_ID, TENANT_A);

        assertThat(result).extracting(RetroActionResponse::id)
                .containsExactly(earlierDueDate.getId(), laterDueDate.getId(), noDueDate.getId());
    }

    /**
     * Given actions with differing statuses, when listForTeam() is called with {@code
     * sort=status}, then results are ordered by the {@link RetroActionStatus} declaration order.
     */
    @Test
    void listForTeam_sortByStatus_ordersByDeclarationOrder() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        RetroAction abandoned = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        abandoned.setStatus(RetroActionStatus.ABANDONNEE);
        RetroAction todo = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of(abandoned, todo));

        List<RetroActionResponse> result = service.listForTeam(TEAM_ID, null, "status", MEMBER_ID, TENANT_A);

        assertThat(result).extracting(RetroActionResponse::id).containsExactly(todo.getId(), abandoned.getId());
    }

    /**
     * Given a status filter value outside the {@link RetroActionStatus} enumeration, when
     * listForTeam() is called, then it throws {@link InvalidRetroActionStatusException} (400).
     */
    @Test
    void listForTeam_invalidStatusFilter_throwsInvalidRetroActionStatusException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        assertThatThrownBy(() -> service.listForTeam(TEAM_ID, "NOT_A_STATUS", null, MEMBER_ID, TENANT_A))
                .isInstanceOf(InvalidRetroActionStatusException.class);
    }

    /**
     * Given an unknown team id, when listForTeam() is called, then it throws {@link
     * TeamNotFoundException} (404).
     */
    @Test
    void listForTeam_unknownTeam_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForTeam(TEAM_ID, null, null, MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
    }

    /**
     * Given a team belonging to a different tenant, when listForTeam() is called, then it throws
     * {@link TeamNotFoundException} (404) — never confirming cross-tenant existence.
     */
    @Test
    void listForTeam_crossTenantTeam_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_B)));

        assertThatThrownBy(() -> service.listForTeam(TEAM_ID, null, null, MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    /**
     * Given a team that exists in the caller's tenant but the caller is not one of its members,
     * when listForTeam() is called, then it throws {@link TeamNotFoundException} (404) — never a
     * 403 (US20.3.1 AC).
     */
    @Test
    void listForTeam_callerNotTeamMember_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, NON_MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForTeam(TEAM_ID, null, null, NON_MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // listPendingForTeam() — US20.3.2
    // -------------------------------------------------------------------------

    /**
     * Given actions in all 4 statuses with differing due dates, when listPendingForTeam() is
     * called, then only A_FAIRE/EN_COURS actions are returned, sorted by ascending due date with
     * the due-date-less one last — TERMINEE/ABANDONNEE are excluded entirely.
     */
    @Test
    void listPendingForTeam_filtersToOpenStatusesAndSortsByDueDateNullsLast() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));

        RetroAction noDueDate = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        noDueDate.setStatus(RetroActionStatus.EN_COURS);
        RetroAction laterDueDate = actionWithDueDate(UUID.randomUUID(), TENANT_A, TEAM_ID, LocalDate.of(2026, 9, 1));
        RetroAction earlierDueDate = actionWithDueDate(UUID.randomUUID(), TENANT_A, TEAM_ID, LocalDate.of(2026, 8, 1));
        earlierDueDate.setStatus(RetroActionStatus.EN_COURS);
        RetroAction completed = actionWithDueDate(UUID.randomUUID(), TENANT_A, TEAM_ID, LocalDate.of(2026, 7, 1));
        completed.setStatus(RetroActionStatus.TERMINEE);
        RetroAction abandoned = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        abandoned.setStatus(RetroActionStatus.ABANDONNEE);
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID))
                .thenReturn(List.of(noDueDate, laterDueDate, earlierDueDate, completed, abandoned));

        List<RetroActionResponse> result = service.listPendingForTeam(TEAM_ID, MEMBER_ID, TENANT_A);

        assertThat(result).extracting(RetroActionResponse::id)
                .containsExactly(earlierDueDate.getId(), laterDueDate.getId(), noDueDate.getId());
        assertThat(result).extracting(RetroActionResponse::status)
                .containsOnly("A_FAIRE", "EN_COURS");
    }

    /**
     * Given a team with actions but none in A_FAIRE/EN_COURS, when listPendingForTeam() is
     * called, then it returns an empty list — never a 404 for that case.
     */
    @Test
    void listPendingForTeam_noOpenActions_returnsEmptyList() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        RetroAction completed = actionWithId(UUID.randomUUID(), TENANT_A, TEAM_ID);
        completed.setStatus(RetroActionStatus.TERMINEE);
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of(completed));

        List<RetroActionResponse> result = service.listPendingForTeam(TEAM_ID, MEMBER_ID, TENANT_A);

        assertThat(result).isEmpty();
    }

    /**
     * Given a team with no actions at all, when listPendingForTeam() is called, then it returns
     * an empty list.
     */
    @Test
    void listPendingForTeam_noActionsAtAll_returnsEmptyList() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, MEMBER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, MEMBER_ID)));
        when(actionRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of());

        List<RetroActionResponse> result = service.listPendingForTeam(TEAM_ID, MEMBER_ID, TENANT_A);

        assertThat(result).isEmpty();
    }

    /**
     * Given an unknown team id, when listPendingForTeam() is called, then it throws {@link
     * TeamNotFoundException} (404).
     */
    @Test
    void listPendingForTeam_unknownTeam_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listPendingForTeam(TEAM_ID, MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
    }

    /**
     * Given a team belonging to a different tenant, when listPendingForTeam() is called, then it
     * throws {@link TeamNotFoundException} (404) — never confirming cross-tenant existence.
     */
    @Test
    void listPendingForTeam_crossTenantTeam_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_B)));

        assertThatThrownBy(() -> service.listPendingForTeam(TEAM_ID, MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    /**
     * Given a team that exists in the caller's tenant but the caller is not one of its members,
     * when listPendingForTeam() is called, then it throws {@link TeamNotFoundException} (404) —
     * never a 403 (same anti-enumeration posture as US20.3.1).
     */
    @Test
    void listPendingForTeam_callerNotTeamMember_throwsTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(teamWithId(TEAM_ID, TENANT_A)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, NON_MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listPendingForTeam(TEAM_ID, NON_MEMBER_ID, TENANT_A))
                .isInstanceOf(TeamNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RetroSession session(final Long tenantId, final Long teamId, final RetroPhase phase) {
        RetroSession session = new RetroSession(
                tenantId, teamId, "Sprint Retro", RetroFormat.START_STOP_CONTINUE, null, null,
                FACILITATOR_ID, "ABC123", null, null, null, 3,
                Instant.parse("2026-07-10T18:00:00Z"), Instant.parse("2026-07-10T10:00:00Z"));
        session.setCurrentPhase(phase);
        setField(session, "id", UUID.randomUUID());
        return session;
    }

    private static RetroCard cardWithId(final UUID id, final UUID sessionId) {
        RetroCard card = new RetroCard(sessionId, "went-well", "Content", false, FACILITATOR_ID, Instant.now());
        setField(card, "id", id);
        return card;
    }

    private static RetroAction actionWithId(final UUID id, final Long tenantId, final Long teamId) {
        RetroAction action = new RetroAction(
                tenantId, teamId, UUID.randomUUID(), null, "Task", null, null, FACILITATOR_ID, Instant.now());
        setField(action, "id", id);
        return action;
    }

    private static RetroAction actionWithDueDate(
            final UUID id, final Long tenantId, final Long teamId, final LocalDate dueDate) {
        RetroAction action = new RetroAction(
                tenantId, teamId, UUID.randomUUID(), null, "Task", null, dueDate, FACILITATOR_ID, Instant.now());
        setField(action, "id", id);
        return action;
    }

    private static Team teamWithId(final Long id, final Long tenantId) {
        Team team = new Team(tenantId, "Team " + id);
        setField(team, "id", id);
        return team;
    }

    /**
     * Force-sets an otherwise JPA-only-assigned field via reflection — mirrors {@code
     * RetroPhaseServiceTest}/{@code RetroSessionServiceTest}'s use of {@code ReflectionTestUtils}
     * for the same purpose.
     */
    private static void setField(final Object target, final String fieldName, final Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set field in test", ex);
        }
    }
}
