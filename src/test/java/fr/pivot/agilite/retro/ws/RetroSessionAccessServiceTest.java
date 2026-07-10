package fr.pivot.agilite.retro.ws;

import fr.pivot.agilite.exception.RetroSessionExpiredException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.ws.dto.RetroParticipantAccessResponse;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroSessionAccessService} (US20.1.2a) — the frictionless
 * authenticated-or-anonymous join flow that mints session access grants.
 */
class RetroSessionAccessServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long FACILITATOR_ID = 99L;
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private RetroSessionRepository sessionRepository;
    private RetroAccessGrantService grantService;
    private AuthenticatedPrincipalResolver principalResolver;
    private RetroSessionAccessService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(RetroSessionRepository.class);
        grantService = mock(RetroAccessGrantService.class);
        principalResolver = mock(AuthenticatedPrincipalResolver.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new RetroSessionAccessService(sessionRepository, grantService, principalResolver, clock);
    }

    /**
     * Given the facilitator's valid, same-tenant bearer token, when they join, then the grant is
     * marked facilitator and both topic destinations are exposed.
     */
    @Test
    void join_facilitatorWithValidToken_returnsFacilitatorGrant() {
        RetroSession session = session();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(principalResolver.resolve("facilitator-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(FACILITATOR_ID, TENANT_ID, "ROLE_USER")));

        RetroParticipantAccessResponse response = service.join(session.getId(), "facilitator-token");

        assertThat(response.facilitator()).isTrue();
        assertThat(response.facilitatorTopicDestination()).isEqualTo(RetroSessionDestinations.facilitatorTopic(session.getId()));
        assertThat(response.topicDestination()).isEqualTo(RetroSessionDestinations.roomTopic(session.getId()));
        assertThat(response.submitDestination()).contains(session.getId().toString());

        ArgumentCaptor<RetroParticipantGrant> grantCaptor = ArgumentCaptor.forClass(RetroParticipantGrant.class);
        verify(grantService).grantAccess(eq(session.getId()), anyString(), grantCaptor.capture(), any(Duration.class));
        assertThat(grantCaptor.getValue().userId()).isEqualTo(FACILITATOR_ID);
        assertThat(grantCaptor.getValue().facilitator()).isTrue();
    }

    /**
     * Given a non-facilitator team member's valid, same-tenant token, when they join, then the
     * grant carries their identity but is not marked facilitator, and no facilitator topic is
     * exposed.
     */
    @Test
    void join_nonFacilitatorMember_returnsNonFacilitatorGrant() {
        RetroSession session = session();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(principalResolver.resolve("member-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(1L, TENANT_ID, "ROLE_USER")));

        RetroParticipantAccessResponse response = service.join(session.getId(), "member-token");

        assertThat(response.facilitator()).isFalse();
        assertThat(response.facilitatorTopicDestination()).isNull();
    }

    /**
     * Given no bearer token at all, when a participant joins, then they are granted access as an
     * anonymous participant — the frictionless join-by-code design pillar.
     */
    @Test
    void join_noToken_returnsAnonymousGrant() {
        RetroSession session = session();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        RetroParticipantAccessResponse response = service.join(session.getId(), null);

        assertThat(response.facilitator()).isFalse();
        ArgumentCaptor<RetroParticipantGrant> grantCaptor = ArgumentCaptor.forClass(RetroParticipantGrant.class);
        verify(grantService).grantAccess(eq(session.getId()), anyString(), grantCaptor.capture(), any(Duration.class));
        assertThat(grantCaptor.getValue().userId()).isNull();
        assertThat(grantCaptor.getValue().tenantId()).isNull();
    }

    /**
     * Security: given a bearer token that resolves to a principal from a *different* tenant than
     * the session's own, when they join, then the identity is silently downgraded to anonymous —
     * never attached to a card as a cross-tenant author.
     */
    @Test
    void join_crossTenantToken_downgradesToAnonymous() {
        RetroSession session = session();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(principalResolver.resolve("other-tenant-token"))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(555L, 999L, "ROLE_USER")));

        RetroParticipantAccessResponse response = service.join(session.getId(), "other-tenant-token");

        assertThat(response.facilitator()).isFalse();
        ArgumentCaptor<RetroParticipantGrant> grantCaptor = ArgumentCaptor.forClass(RetroParticipantGrant.class);
        verify(grantService).grantAccess(eq(session.getId()), anyString(), grantCaptor.capture(), any(Duration.class));
        assertThat(grantCaptor.getValue().userId()).isNull();
    }

    /**
     * Given an invalid/expired/unknown bearer token, when a participant joins, then they are
     * silently downgraded to anonymous rather than rejected.
     */
    @Test
    void join_invalidToken_downgradesToAnonymous() {
        RetroSession session = session();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(principalResolver.resolve("bad-token")).thenReturn(Optional.empty());

        RetroParticipantAccessResponse response = service.join(session.getId(), "bad-token");

        assertThat(response.facilitator()).isFalse();
    }

    /**
     * Given an unknown session id, when a join is attempted, then it is rejected as not-found.
     */
    @Test
    void join_unknownSession_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(sessionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(unknown, null)).isInstanceOf(RetroSessionNotFoundException.class);
    }

    /**
     * Given a session already CLOSED, when a new join is attempted, then it is rejected as
     * expired/gone.
     */
    @Test
    void join_closedSession_throwsExpired() {
        RetroSession session = session();
        session.setCurrentPhase(RetroPhase.CLOSED);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.join(session.getId(), null))
                .isInstanceOf(RetroSessionExpiredException.class);
    }

    /**
     * Given a session whose {@code expiresAt} has already passed, when a new join is attempted,
     * then it is rejected as expired/gone.
     */
    @Test
    void join_expiredSession_throwsExpired() {
        RetroSession session = new RetroSession(
                TENANT_ID, 1L, "Sprint Retro", RetroFormat.START_STOP_CONTINUE, null,
                FACILITATOR_ID, "ABC123", null, null, null, 3,
                NOW.minusSeconds(60), NOW.minusSeconds(3600));
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.join(session.getId(), null))
                .isInstanceOf(RetroSessionExpiredException.class);
    }

    private static RetroSession session() {
        RetroSession session = new RetroSession(
                TENANT_ID, 1L, "Sprint Retro", RetroFormat.START_STOP_CONTINUE, null,
                FACILITATOR_ID, "ABC123", null, null, null, 3,
                NOW.plusSeconds(3600), NOW.minusSeconds(60));
        // id is normally only assigned by JPA on persist; force-set so session.getId() is
        // realistic rather than null.
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        return session;
    }
}
