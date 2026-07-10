package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.exception.RetroFacilitatorOnlyException;
import fr.pivot.agilite.exception.RetroInvalidPhaseTransitionException;
import fr.pivot.agilite.exception.RetroSessionNotFoundException;
import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.phase.dto.PhaseChangedEvent;
import fr.pivot.agilite.retro.phase.dto.RevealResponse;
import fr.pivot.agilite.retro.phase.dto.SessionClosedEvent;
import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.vote.RetroVoteRepository;
import fr.pivot.agilite.retro.vote.dto.RankedCard;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroPhaseService} (US20.1.2a/b) — facilitator/tenant/phase gating for
 * manual contribution close, reveal, vote-open/close, plus the reveal grouping-by-column and
 * vote-count ranking logic.
 */
class RetroPhaseServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long FACILITATOR_ID = 99L;
    private static final Long OTHER_USER_ID = 100L;

    private RetroSessionRepository sessionRepository;
    private RetroCardRepository cardRepository;
    private RetroVoteRepository voteRepository;
    private SimpMessagingTemplate messagingTemplate;
    private RetroPhaseService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(RetroSessionRepository.class);
        cardRepository = mock(RetroCardRepository.class);
        voteRepository = mock(RetroVoteRepository.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);
        service = new RetroPhaseService(sessionRepository, cardRepository, voteRepository, messagingTemplate, clock);
        when(sessionRepository.save(any(RetroSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cardRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class))).thenReturn(List.of());
        when(voteRepository.countVotesBySession(any(UUID.class))).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // closeContribution
    // -------------------------------------------------------------------------

    /**
     * Given the facilitator, when they manually close contribution on a CONTRIBUTION-phase
     * session, then it transitions to REVUE and PHASE_CHANGED is broadcast.
     */
    @Test
    void closeContribution_asFacilitator_transitionsAndBroadcasts() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        RetroPhase result = service.closeContribution(session.getId(), FACILITATOR_ID, TENANT_ID);

        assertThat(result).isEqualTo(RetroPhase.REVUE);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.REVUE);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        PhaseChangedEvent event = (PhaseChangedEvent) captor.getValue();
        assertThat(event.previousPhase()).isEqualTo(RetroPhase.CONTRIBUTION);
        assertThat(event.currentPhase()).isEqualTo(RetroPhase.REVUE);
    }

    /**
     * Given a caller who is not the facilitator, when they attempt to close contribution, then
     * it is rejected and no transition happens.
     */
    @Test
    void closeContribution_notFacilitator_throwsAndDoesNotTransition() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeContribution(session.getId(), OTHER_USER_ID, TENANT_ID))
                .isInstanceOf(RetroFacilitatorOnlyException.class);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.CONTRIBUTION);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Given a session already past CONTRIBUTION, when the facilitator attempts to close it
     * again, then it is rejected with a conflict.
     */
    @Test
    void closeContribution_alreadyRevue_throwsInvalidTransition() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeContribution(session.getId(), FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroInvalidPhaseTransitionException.class);
    }

    /**
     * Given a session belonging to a different tenant, when closing contribution is attempted,
     * then it is rejected as not-found (never confirming cross-tenant existence).
     */
    @Test
    void closeContribution_crossTenant_throwsNotFound() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeContribution(session.getId(), FACILITATOR_ID, 999L))
                .isInstanceOf(RetroSessionNotFoundException.class);
    }

    /**
     * Given an unknown session id, when closing contribution is attempted, then it is rejected
     * as not-found.
     */
    @Test
    void closeContribution_unknownSession_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(sessionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeContribution(unknown, FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroSessionNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // autoTransitionToRevue
    // -------------------------------------------------------------------------

    /**
     * Given a session in CONTRIBUTION, when the scheduler triggers the auto-transition, then it
     * moves to REVUE and PHASE_CHANGED is broadcast — no caller identity needed.
     */
    @Test
    void autoTransitionToRevue_contributionPhase_transitions() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToRevue(session.getId());

        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.REVUE);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), any(Object.class));
    }

    /**
     * Given a session already advanced past CONTRIBUTION (e.g. manually closed already), when
     * the scheduler's auto-transition runs, then it is a no-op — no double transition, no
     * duplicate broadcast.
     */
    @Test
    void autoTransitionToRevue_alreadyPastContribution_isNoOp() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToRevue(session.getId());

        verify(sessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // -------------------------------------------------------------------------
    // reveal
    // -------------------------------------------------------------------------

    /**
     * Given a REVUE-phase session with cards in multiple columns, when the facilitator triggers
     * reveal, then every card is broadcast in clear, grouped by column, and returned identically
     * in the REST response.
     */
    @Test
    void reveal_asFacilitatorInRevuePhase_broadcastsGroupedByColumn() {
        RetroSession session = session(RetroPhase.REVUE);
        UUID card1 = UUID.randomUUID();
        UUID card2 = UUID.randomUUID();
        UUID card3 = UUID.randomUUID();
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(cardRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(
                cardWithId(card1, session.getId(), "went-well", "Good pace", false, FACILITATOR_ID),
                cardWithId(card2, session.getId(), "to-improve", "Too many meetings", true, null),
                cardWithId(card3, session.getId(), "went-well", "Great teamwork", false, OTHER_USER_ID)));

        RevealResponse response = service.reveal(session.getId(), FACILITATOR_ID, TENANT_ID);

        assertThat(response.cardCount()).isEqualTo(3);
        assertThat(response.columns()).containsKeys("went-well", "to-improve");
        assertThat(response.columns().get("went-well")).hasSize(2);
        assertThat(response.columns().get("to-improve")).hasSize(1);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        assertThat(captor.getValue().toString()).contains("Good pace", "Too many meetings", "Great teamwork");
    }

    /**
     * Given a session still in CONTRIBUTION (never closed), when reveal is attempted, then it is
     * rejected with a conflict — reveal requires REVUE to have been reached first.
     */
    @Test
    void reveal_stillInContribution_throwsInvalidTransition() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.reveal(session.getId(), FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroInvalidPhaseTransitionException.class);
    }

    /**
     * Given a non-facilitator caller, when reveal is attempted, then it is rejected.
     */
    @Test
    void reveal_notFacilitator_throws() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.reveal(session.getId(), OTHER_USER_ID, TENANT_ID))
                .isInstanceOf(RetroFacilitatorOnlyException.class);
    }

    // -------------------------------------------------------------------------
    // openVote
    // -------------------------------------------------------------------------

    /**
     * Given the facilitator, when they open the vote phase on a REVUE-phase session, then it
     * transitions to VOTE and PHASE_CHANGED is broadcast.
     */
    @Test
    void openVote_asFacilitatorInRevuePhase_transitionsAndBroadcasts() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        RetroPhase result = service.openVote(session.getId(), FACILITATOR_ID, TENANT_ID);

        assertThat(result).isEqualTo(RetroPhase.VOTE);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.VOTE);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        PhaseChangedEvent event = (PhaseChangedEvent) captor.getValue();
        assertThat(event.previousPhase()).isEqualTo(RetroPhase.REVUE);
        assertThat(event.currentPhase()).isEqualTo(RetroPhase.VOTE);
        assertThat(event.rankedCards()).isNull();
    }

    /**
     * Given a caller who is not the facilitator, when they attempt to open the vote phase, then
     * it is rejected and no transition happens.
     */
    @Test
    void openVote_notFacilitator_throwsAndDoesNotTransition() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.openVote(session.getId(), OTHER_USER_ID, TENANT_ID))
                .isInstanceOf(RetroFacilitatorOnlyException.class);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.REVUE);
    }

    /**
     * Given a session still in CONTRIBUTION (reveal never triggered), when the facilitator
     * attempts to open the vote phase, then it is rejected with a conflict.
     */
    @Test
    void openVote_stillInContribution_throwsInvalidTransition() {
        RetroSession session = session(RetroPhase.CONTRIBUTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.openVote(session.getId(), FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroInvalidPhaseTransitionException.class);
    }

    // -------------------------------------------------------------------------
    // closeVote / autoTransitionToAction — including ranking
    // -------------------------------------------------------------------------

    /**
     * Given the facilitator, when they close the vote phase on a VOTE-phase session, then it
     * transitions to ACTION and PHASE_CHANGED is broadcast carrying the vote-count ranking.
     */
    @Test
    void closeVote_asFacilitatorInVotePhase_transitionsAndBroadcastsRanking() {
        RetroSession session = session(RetroPhase.VOTE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        UUID card1 = UUID.randomUUID();
        when(cardRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(
                cardWithId(card1, session.getId(), "went-well", "Good pace", false, FACILITATOR_ID)));
        when(voteRepository.countVotesBySession(session.getId())).thenReturn(List.of(voteCount(card1, 5L)));

        RetroPhase result = service.closeVote(session.getId(), FACILITATOR_ID, TENANT_ID);

        assertThat(result).isEqualTo(RetroPhase.ACTION);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.ACTION);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        PhaseChangedEvent event = (PhaseChangedEvent) captor.getValue();
        assertThat(event.previousPhase()).isEqualTo(RetroPhase.VOTE);
        assertThat(event.currentPhase()).isEqualTo(RetroPhase.ACTION);
        assertThat(event.rankedCards()).hasSize(1);
        assertThat(event.rankedCards().get(0).cardId()).isEqualTo(card1);
        assertThat(event.rankedCards().get(0).voteCount()).isEqualTo(5L);
    }

    /**
     * Given several cards with differing vote counts (including one with zero votes) and a tie
     * between two cards, when the ranking is built, then it is ordered by vote count descending,
     * the zero-vote card is last, and the tie is broken by original submission order.
     */
    @Test
    void closeVote_ranking_ordersByVoteCountDescendingWithZeroVotesLastAndStableTieBreak() {
        RetroSession session = session(RetroPhase.VOTE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        UUID card1 = UUID.randomUUID();
        UUID card2 = UUID.randomUUID();
        UUID card3 = UUID.randomUUID();
        // Submission order: card1, card2, card3. card1 and card3 tie at 2 votes; card2 has none.
        when(cardRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(
                cardWithId(card1, session.getId(), "went-well", "First", false, FACILITATOR_ID),
                cardWithId(card2, session.getId(), "went-well", "Never voted", false, FACILITATOR_ID),
                cardWithId(card3, session.getId(), "went-well", "Third", false, FACILITATOR_ID)));
        when(voteRepository.countVotesBySession(session.getId())).thenReturn(List.of(
                voteCount(card1, 2L), voteCount(card3, 2L)));

        service.closeVote(session.getId(), FACILITATOR_ID, TENANT_ID);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        List<RankedCard> ranking = ((PhaseChangedEvent) captor.getValue()).rankedCards();
        assertThat(ranking).extracting(RankedCard::cardId).containsExactly(card1, card3, card2);
        assertThat(ranking.get(2).voteCount()).isZero();
    }

    /**
     * Given a caller who is not the facilitator, when they attempt to close the vote phase, then
     * it is rejected and no transition happens.
     */
    @Test
    void closeVote_notFacilitator_throwsAndDoesNotTransition() {
        RetroSession session = session(RetroPhase.VOTE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeVote(session.getId(), OTHER_USER_ID, TENANT_ID))
                .isInstanceOf(RetroFacilitatorOnlyException.class);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.VOTE);
    }

    /**
     * Given a session still in REVUE (vote never opened), when the facilitator attempts to close
     * the vote phase, then it is rejected with a conflict.
     */
    @Test
    void closeVote_stillInRevue_throwsInvalidTransition() {
        RetroSession session = session(RetroPhase.REVUE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeVote(session.getId(), FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroInvalidPhaseTransitionException.class);
    }

    /**
     * Given a session in VOTE, when the scheduler triggers the auto-transition, then it moves to
     * ACTION, broadcasting PHASE_CHANGED with the ranking — no caller identity needed.
     */
    @Test
    void autoTransitionToAction_votePhase_transitionsAndBroadcastsRanking() {
        RetroSession session = session(RetroPhase.VOTE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToAction(session.getId());

        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.ACTION);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        assertThat(((PhaseChangedEvent) captor.getValue()).rankedCards()).isNotNull();
    }

    /**
     * Given a session already advanced past VOTE (e.g. manually closed already), when the
     * scheduler's auto-transition runs, then it is a no-op — no double transition, no duplicate
     * broadcast.
     */
    @Test
    void autoTransitionToAction_alreadyPastVote_isNoOp() {
        RetroSession session = session(RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToAction(session.getId());

        verify(sessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // -------------------------------------------------------------------------
    // closeSession / autoTransitionToClose
    // -------------------------------------------------------------------------

    /**
     * Given the facilitator, when they manually close the session on an ACTION-phase session,
     * then it transitions to CLOSED and SESSION_CLOSED is broadcast.
     */
    @Test
    void closeSession_asFacilitatorInActionPhase_transitionsAndBroadcastsSessionClosed() {
        RetroSession session = session(RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        RetroPhase result = service.closeSession(session.getId(), FACILITATOR_ID, TENANT_ID);

        assertThat(result).isEqualTo(RetroPhase.CLOSED);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.CLOSED);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        SessionClosedEvent event = (SessionClosedEvent) captor.getValue();
        assertThat(event.type()).isEqualTo(SessionClosedEvent.TYPE);
        assertThat(event.sessionId()).isEqualTo(session.getId());
        assertThat(event.previousPhase()).isEqualTo(RetroPhase.ACTION);
        assertThat(event.closedAt()).isNotNull();
    }

    /**
     * Given a caller who is not the facilitator, when they attempt to close the session, then it
     * is rejected (403-equivalent) and no transition happens.
     */
    @Test
    void closeSession_notFacilitator_throwsAndDoesNotTransition() {
        RetroSession session = session(RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeSession(session.getId(), OTHER_USER_ID, TENANT_ID))
                .isInstanceOf(RetroFacilitatorOnlyException.class);
        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.ACTION);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Given a session still in VOTE (vote never closed), when the facilitator attempts to close
     * the session, then it is rejected with a conflict.
     */
    @Test
    void closeSession_stillInVote_throwsInvalidTransition() {
        RetroSession session = session(RetroPhase.VOTE);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeSession(session.getId(), FACILITATOR_ID, TENANT_ID))
                .isInstanceOf(RetroInvalidPhaseTransitionException.class);
    }

    /**
     * Given a session belonging to a different tenant, when closing the session is attempted,
     * then it is rejected as not-found (never confirming cross-tenant existence).
     */
    @Test
    void closeSession_crossTenant_throwsNotFound() {
        RetroSession session = session(RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.closeSession(session.getId(), FACILITATOR_ID, 999L))
                .isInstanceOf(RetroSessionNotFoundException.class);
    }

    /**
     * Given a session in ACTION, when the scheduler triggers the auto-transition, then it moves
     * to CLOSED, broadcasting SESSION_CLOSED — no caller identity needed.
     */
    @Test
    void autoTransitionToClose_actionPhase_transitionsAndBroadcastsSessionClosed() {
        RetroSession session = session(RetroPhase.ACTION);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToClose(session.getId());

        assertThat(session.getCurrentPhase()).isEqualTo(RetroPhase.CLOSED);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(session.getId())), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionClosedEvent.class);
    }

    /**
     * Given a session already closed (e.g. manually closed already), when the scheduler's
     * auto-transition runs, then it is a no-op — no double transition, no duplicate broadcast.
     */
    @Test
    void autoTransitionToClose_alreadyClosed_isNoOp() {
        RetroSession session = session(RetroPhase.CLOSED);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));

        service.autoTransitionToClose(session.getId());

        verify(sessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    /**
     * Builds a test double for {@link RetroVoteRepository.CardVoteCount}.
     */
    private static RetroVoteRepository.CardVoteCount voteCount(final UUID cardId, final long count) {
        return new RetroVoteRepository.CardVoteCount() {
            @Override
            public UUID getCardId() {
                return cardId;
            }

            @Override
            public long getVoteCount() {
                return count;
            }
        };
    }

    /**
     * Builds a {@link RetroCard} with its {@code id} force-set via reflection — mirrors {@code
     * PokerChannelInterceptorTest}'s use of {@link ReflectionTestUtils} for otherwise-inaccessible
     * fields; {@code id} is normally only ever assigned by JPA on persist.
     */
    private static RetroCard cardWithId(
            final UUID id, final UUID sessionId, final String columnKey,
            final String content, final boolean anonymous, final Long authorUserId) {
        RetroCard card = new RetroCard(sessionId, columnKey, content, anonymous, authorUserId, Instant.now());
        ReflectionTestUtils.setField(card, "id", id);
        return card;
    }

    private static RetroSession session(final RetroPhase phase) {
        RetroSession session = new RetroSession(
                TENANT_ID, 1L, "Sprint Retro", RetroFormat.START_STOP_CONTINUE, null, null,
                FACILITATOR_ID, "ABC123", null, null, null, 3,
                Instant.parse("2026-07-10T18:00:00Z"), Instant.parse("2026-07-10T10:00:00Z"));
        session.setCurrentPhase(phase);
        // id is normally only assigned by JPA on persist; force-set here (mirrors cardWithId
        // above) so session.getId() is realistic rather than null in assertions/logging.
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        return session;
    }
}
