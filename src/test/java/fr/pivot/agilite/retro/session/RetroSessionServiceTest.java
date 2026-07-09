package fr.pivot.agilite.retro.session;

import fr.pivot.agilite.exception.InvalidRetroFormatException;
import fr.pivot.agilite.exception.RetroJoinCodeNotFoundException;
import fr.pivot.agilite.exception.RetroSessionExpiredException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.exception.RetroTeamAccessDeniedException;
import fr.pivot.agilite.exception.RetroTeamNotFoundException;
import fr.pivot.agilite.retro.session.dto.CreateRetroSessionRequest;
import fr.pivot.agilite.retro.session.dto.RetroSessionJoinResponse;
import fr.pivot.agilite.retro.session.dto.RetroSessionResponse;
import fr.pivot.core.team.Team;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import fr.pivot.core.team.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroSessionService} covering all business branches (US20.1.1).
 *
 * <p>All external dependencies (repositories, join code generator) are mocked via Mockito.
 * No Spring context is loaded — tests are fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class RetroSessionServiceTest {

    @Mock
    private RetroSessionRepository sessionRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private JoinCodeGenerator joinCodeGenerator;

    private RetroSessionService service;

    private static final Long CALLER_ID = 1L;
    private static final Long TENANT_A = 100L;
    private static final Long TENANT_B = 200L;
    private static final Long TEAM_ID = 10L;
    private static final String JOIN_CODE = "ABC123";

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        service = new RetroSessionService(
                sessionRepository, teamRepository, teamMemberRepository, joinCodeGenerator);
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    /**
     * Given a valid team the caller is a member of, when create() is called,
     * then it returns a session with the caller as facilitator, phase CONTRIBUTION,
     * the generated join code, and a 24h expiry.
     */
    @Test
    void create_whenTeamValidAndCallerMember_returnsCreatedSession() {
        Team team = teamWithId(TEAM_ID, TENANT_A, "Team A");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(joinCodeGenerator.generate()).thenReturn(JOIN_CODE);
        when(sessionRepository.save(any(RetroSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Sprint 12 Retro", "START_STOP_CONTINUE", TEAM_ID, "SPRINT-12",
                null, null, null, null);

        Instant before = Instant.now();
        RetroSessionResponse response = service.create(request, CALLER_ID, TENANT_A);
        Instant after = Instant.now();

        assertThat(response.title()).isEqualTo("Sprint 12 Retro");
        assertThat(response.format()).isEqualTo(RetroFormat.START_STOP_CONTINUE);
        assertThat(response.teamId()).isEqualTo(TEAM_ID);
        assertThat(response.facilitatorUserId()).isEqualTo(CALLER_ID);
        assertThat(response.joinCode()).isEqualTo(JOIN_CODE);
        assertThat(response.currentPhase()).isEqualTo(RetroPhase.CONTRIBUTION);
        assertThat(response.voteCountPerParticipant()).isEqualTo(3);
        assertThat(response.sprintRef()).isEqualTo("SPRINT-12");
        assertThat(response.expiresAt()).isBetween(
                before.plusSeconds(24 * 3600 - 5), after.plusSeconds(24 * 3600 + 5));
    }

    /**
     * Given explicit optional fields (timers, vote count), when create() is called,
     * then they are all persisted as given rather than defaulted.
     */
    @Test
    void create_withExplicitOptionalFields_persistsThemAsGiven() {
        Team team = teamWithId(TEAM_ID, TENANT_A, "Team A");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(joinCodeGenerator.generate()).thenReturn(JOIN_CODE);
        when(sessionRepository.save(any(RetroSession.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Retro", "KIF_KAF", TEAM_ID, null, 300, 120, 600, 5);

        RetroSessionResponse response = service.create(request, CALLER_ID, TENANT_A);

        assertThat(response.contributionTimerSeconds()).isEqualTo(300);
        assertThat(response.voteTimerSeconds()).isEqualTo(120);
        assertThat(response.actionTimerSeconds()).isEqualTo(600);
        assertThat(response.voteCountPerParticipant()).isEqualTo(5);
    }

    /**
     * Given a {@code teamId} that does not exist, when create() is called,
     * then it throws {@link RetroTeamNotFoundException} (404) — never 403.
     */
    @Test
    void create_whenTeamDoesNotExist_throwsRetroTeamNotFoundException() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Retro", "START_STOP_CONTINUE", TEAM_ID, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request, CALLER_ID, TENANT_A))
                .isInstanceOf(RetroTeamNotFoundException.class);
        verify(sessionRepository, never()).save(any());
    }

    /**
     * Given a {@code teamId} that exists but belongs to a different tenant, when create() is
     * called, then it throws {@link RetroTeamNotFoundException} (404) — cross-tenant existence
     * is never confirmed via a 403.
     */
    @Test
    void create_whenTeamBelongsToDifferentTenant_throwsRetroTeamNotFoundException() {
        Team team = teamWithId(TEAM_ID, TENANT_B, "Team B");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Retro", "START_STOP_CONTINUE", TEAM_ID, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request, CALLER_ID, TENANT_A))
                .isInstanceOf(RetroTeamNotFoundException.class);
        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }

    /**
     * Given the team exists in the caller's tenant but the caller is not a member,
     * when create() is called, then it throws {@link RetroTeamAccessDeniedException} (403).
     */
    @Test
    void create_whenCallerNotTeamMember_throwsRetroTeamAccessDeniedException() {
        Team team = teamWithId(TEAM_ID, TENANT_A, "Team A");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.empty());

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Retro", "START_STOP_CONTINUE", TEAM_ID, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request, CALLER_ID, TENANT_A))
                .isInstanceOf(RetroTeamAccessDeniedException.class);
        verify(sessionRepository, never()).save(any());
    }

    /**
     * Given a {@code format} value outside the {@link RetroFormat} catalogue, when create()
     * is called, then it throws {@link InvalidRetroFormatException} (400).
     */
    @Test
    void create_whenFormatInvalid_throwsInvalidRetroFormatException() {
        Team team = teamWithId(TEAM_ID, TENANT_A, "Team A");
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));

        CreateRetroSessionRequest request = new CreateRetroSessionRequest(
                "Retro", "NOT_A_FORMAT", TEAM_ID, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(request, CALLER_ID, TENANT_A))
                .isInstanceOf(InvalidRetroFormatException.class);
        verify(sessionRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // findByIdForTenant()
    // -------------------------------------------------------------------------

    /**
     * Given a session that exists for the caller's tenant, when findByIdForTenant() is called,
     * then it returns the full detail regardless of phase.
     */
    @Test
    void findByIdForTenant_whenFoundAndClosed_returnsFullDetail() {
        UUID sessionId = UUID.randomUUID();
        RetroSession session = newSession(TENANT_A, TEAM_ID, RetroPhase.CLOSED);
        when(sessionRepository.findByIdAndTenantId(sessionId, TENANT_A))
                .thenReturn(Optional.of(session));

        RetroSessionResponse response = service.findByIdForTenant(sessionId, TENANT_A);

        assertThat(response.currentPhase()).isEqualTo(RetroPhase.CLOSED);
    }

    /**
     * Given no session matches id+tenantId, when findByIdForTenant() is called,
     * then it throws {@link RetroSessionNotFoundException}.
     */
    @Test
    void findByIdForTenant_whenNotFound_throwsRetroSessionNotFoundException() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndTenantId(sessionId, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByIdForTenant(sessionId, TENANT_A))
                .isInstanceOf(RetroSessionNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // findByJoinCode()
    // -------------------------------------------------------------------------

    /**
     * Given a joinable session (not expired, not closed), when findByJoinCode() is called,
     * then it returns the minimal join metadata.
     */
    @Test
    void findByJoinCode_whenJoinable_returnsMinimalMetadata() {
        RetroSession session = newSession(TENANT_A, TEAM_ID, RetroPhase.CONTRIBUTION);
        when(sessionRepository.findByJoinCode(JOIN_CODE)).thenReturn(Optional.of(session));

        RetroSessionJoinResponse response = service.findByJoinCode(JOIN_CODE);

        assertThat(response.title()).isEqualTo(session.getTitle());
        assertThat(response.currentPhase()).isEqualTo(RetroPhase.CONTRIBUTION);
    }

    /**
     * Given an unknown join code, when findByJoinCode() is called,
     * then it throws {@link RetroJoinCodeNotFoundException}.
     */
    @Test
    void findByJoinCode_whenUnknown_throwsRetroJoinCodeNotFoundException() {
        when(sessionRepository.findByJoinCode(JOIN_CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByJoinCode(JOIN_CODE))
                .isInstanceOf(RetroJoinCodeNotFoundException.class);
    }

    /**
     * Given a session whose phase is CLOSED, when findByJoinCode() is called,
     * then it throws {@link RetroSessionExpiredException} (410).
     */
    @Test
    void findByJoinCode_whenClosed_throwsRetroSessionExpiredException() {
        RetroSession session = newSession(TENANT_A, TEAM_ID, RetroPhase.CLOSED);
        when(sessionRepository.findByJoinCode(JOIN_CODE)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.findByJoinCode(JOIN_CODE))
                .isInstanceOf(RetroSessionExpiredException.class);
    }

    /**
     * Given a session whose {@code expiresAt} is in the past, when findByJoinCode() is called,
     * then it throws {@link RetroSessionExpiredException} (410).
     */
    @Test
    void findByJoinCode_whenExpired_throwsRetroSessionExpiredException() {
        RetroSession session = new RetroSession(
                TENANT_A, TEAM_ID, "Retro", RetroFormat.START_STOP_CONTINUE, null,
                CALLER_ID, JOIN_CODE, null, null, null, 3,
                Instant.now().minusSeconds(10), Instant.now().minusSeconds(3600));
        when(sessionRepository.findByJoinCode(JOIN_CODE)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.findByJoinCode(JOIN_CODE))
                .isInstanceOf(RetroSessionExpiredException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link Team} instance with the given id set via reflection, simulating a
     * JPA-persisted entity whose id is assigned by the database.
     */
    private Team teamWithId(final Long id, final Long tenantId, final String name) {
        Team team = new Team(tenantId, name);
        try {
            java.lang.reflect.Field field = Team.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(team, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set team id in test", ex);
        }
        return team;
    }

    /**
     * Builds a joinable (non-expired) {@link RetroSession} in the given phase for tests that
     * only care about phase/expiry branching.
     */
    private RetroSession newSession(final Long tenantId, final Long teamId, final RetroPhase phase) {
        RetroSession session = new RetroSession(
                tenantId, teamId, "Retro", RetroFormat.START_STOP_CONTINUE, null,
                CALLER_ID, JOIN_CODE, null, null, null, 3,
                Instant.now().plusSeconds(3600), Instant.now());
        session.setCurrentPhase(phase);
        return session;
    }
}
