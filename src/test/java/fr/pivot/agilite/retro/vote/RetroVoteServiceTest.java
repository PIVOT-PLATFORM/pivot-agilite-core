package fr.pivot.agilite.retro.vote;

import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroFormat;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
import fr.pivot.agilite.retro.vote.dto.VoteBalanceEvent;
import fr.pivot.agilite.retro.vote.dto.VoteCastEvent;
import fr.pivot.agilite.retro.vote.dto.VoteUncastEvent;
import fr.pivot.agilite.retro.ws.RetroAccessGrantService;
import fr.pivot.agilite.retro.ws.RetroParticipantGrant;
import fr.pivot.agilite.retro.ws.RetroSessionDestinations;
import fr.pivot.agilite.ws.WsErrorPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetroVoteService} (US20.1.2b) — cast/uncast phase and identity gating,
 * server-authoritative balance decrement/increment, cross-session card rejection, and the private
 * balance-notification content, all against mocked collaborators.
 *
 * <p>The concurrency proof (no lost/double-counted votes under genuine contention) is covered
 * separately by {@code RetroVoteConcurrencyIT}, and the full raw-payload STOMP round trip by
 * {@code RetroVoteCastingIT}.
 */
class RetroVoteServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "token-1";
    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private RetroVoteRepository voteRepository;
    private RetroVoteBalanceRepository balanceRepository;
    private RetroCardRepository cardRepository;
    private RetroSessionRepository sessionRepository;
    private RetroAccessGrantService grantService;
    private SimpMessagingTemplate messagingTemplate;
    private Principal principal;
    private RetroVoteService service;

    @BeforeEach
    void setUp() {
        voteRepository = mock(RetroVoteRepository.class);
        balanceRepository = mock(RetroVoteBalanceRepository.class);
        cardRepository = mock(RetroCardRepository.class);
        sessionRepository = mock(RetroSessionRepository.class);
        grantService = mock(RetroAccessGrantService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        principal = () -> "connection-1";
        service = new RetroVoteService(
                voteRepository, balanceRepository, cardRepository, sessionRepository, grantService, messagingTemplate);

        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroParticipantGrant(42L, 7L, false)));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(voteSession()));
        when(cardRepository.findById(CARD_ID)).thenReturn(Optional.of(cardIn(SESSION_ID)));
        when(voteRepository.save(any(RetroVote.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // castVote
    // -------------------------------------------------------------------------

    /**
     * Given a participant with remaining votes and a revealed card in a VOTE-phase session, when
     * they cast a vote, then it is persisted, the card's new aggregate count is broadcast to the
     * room, and the caller alone receives their updated balance.
     */
    @Test
    void castVote_available_persistsBroadcastsAndNotifiesBalance() {
        when(balanceRepository.incrementIfAvailable(SESSION_ID, ACCESS_TOKEN)).thenReturn(1);
        when(voteRepository.countByCardId(CARD_ID)).thenReturn(4L);
        when(balanceRepository.findBySessionIdAndVoterToken(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroVoteBalance(SESSION_ID, ACCESS_TOKEN, 1, 3, NOW)));

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(balanceRepository).ensureBalanceRow(SESSION_ID, ACCESS_TOKEN, 3);
        ArgumentCaptor<RetroVote> voteCaptor = ArgumentCaptor.forClass(RetroVote.class);
        verify(voteRepository).save(voteCaptor.capture());
        assertThat(voteCaptor.getValue().getSessionId()).isEqualTo(SESSION_ID);
        assertThat(voteCaptor.getValue().getCardId()).isEqualTo(CARD_ID);
        assertThat(voteCaptor.getValue().getVoterToken()).isEqualTo(ACCESS_TOKEN);

        ArgumentCaptor<Object> castCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(SESSION_ID)), castCaptor.capture());
        VoteCastEvent event = (VoteCastEvent) castCaptor.getValue();
        assertThat(event.sessionId()).isEqualTo(SESSION_ID);
        assertThat(event.cardId()).isEqualTo(CARD_ID);
        assertThat(event.voteCount()).isEqualTo(4L);

        ArgumentCaptor<Object> balanceCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/votes"), balanceCaptor.capture());
        VoteBalanceEvent balance = (VoteBalanceEvent) balanceCaptor.getValue();
        assertThat(balance.votesRemaining()).isEqualTo(2);
        assertThat(balance.votesAllowed()).isEqualTo(3);
    }

    /**
     * Security AC: given a participant with zero votes remaining, when they attempt to cast a
     * vote, then it is rejected without persisting or broadcasting — no negative decrement, ever.
     */
    @Test
    void castVote_noRemainingVotes_rejectsWithoutPersistingOrBroadcasting() {
        when(balanceRepository.incrementIfAvailable(SESSION_ID, ACCESS_TOKEN)).thenReturn(0);

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(voteRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Error case AC: given a card that belongs to a different session, when a vote is attempted
     * on it, then it is rejected (404-equivalent) without persisting or broadcasting.
     */
    @Test
    void castVote_cardFromDifferentSession_rejectsWithoutPersistingOrBroadcasting() {
        when(cardRepository.findById(CARD_ID)).thenReturn(Optional.of(cardIn(UUID.randomUUID())));

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(balanceRepository, never()).incrementIfAvailable(any(), anyString());
        verify(voteRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given an unknown card id, when a vote is attempted on it, then it is rejected the same way
     * as a cross-session card.
     */
    @Test
    void castVote_unknownCard_rejects() {
        when(cardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(voteRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given a session not currently in VOTE phase, when a vote is attempted, then it is rejected
     * without persisting or broadcasting.
     */
    @Test
    void castVote_sessionNotInVotePhase_rejects() {
        RetroSession session = voteSession();
        session.setCurrentPhase(RetroPhase.REVUE);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(voteRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Given an unknown session, when a vote is attempted, then it is rejected.
     */
    @Test
    void castVote_unknownSession_rejects() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(voteRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    /**
     * Defensive-only: given no valid grant resolves at all (should never happen — the channel
     * interceptor already denies such SEND frames upstream), when a vote is attempted, then it is
     * rejected without persisting or broadcasting.
     */
    @Test
    void castVote_noGrant_rejectsDefensively() {
        when(grantService.resolveGrant(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.empty());

        service.castVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(sessionRepository, never()).findById(any());
        verify(voteRepository, never()).save(any());
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    // -------------------------------------------------------------------------
    // uncastVote
    // -------------------------------------------------------------------------

    /**
     * Given a participant who previously voted on a card, when they uncast it, then exactly one
     * vote row is removed, the card's new aggregate count is broadcast, and the caller alone
     * receives their updated (increased) balance.
     */
    @Test
    void uncastVote_existingVote_removesBroadcastsAndNotifiesBalance() {
        when(voteRepository.deleteOneVote(CARD_ID, ACCESS_TOKEN)).thenReturn(1);
        when(voteRepository.countByCardId(CARD_ID)).thenReturn(1L);
        when(balanceRepository.findBySessionIdAndVoterToken(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroVoteBalance(SESSION_ID, ACCESS_TOKEN, 1, 3, NOW)));

        service.uncastVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(balanceRepository).decrementIfPositive(SESSION_ID, ACCESS_TOKEN);
        ArgumentCaptor<Object> uncastCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(RetroSessionDestinations.roomTopic(SESSION_ID)), uncastCaptor.capture());
        VoteUncastEvent event = (VoteUncastEvent) uncastCaptor.getValue();
        assertThat(event.cardId()).isEqualTo(CARD_ID);
        assertThat(event.voteCount()).isEqualTo(1L);

        ArgumentCaptor<Object> balanceCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/votes"), balanceCaptor.capture());
        assertThat(((VoteBalanceEvent) balanceCaptor.getValue()).votesRemaining()).isEqualTo(2);
    }

    /**
     * Given a participant with no matching vote on the target card, when they attempt to uncast
     * it, then it is rejected without touching the balance or broadcasting.
     */
    @Test
    void uncastVote_noMatchingVote_rejectsWithoutBroadcasting() {
        when(voteRepository.deleteOneVote(CARD_ID, ACCESS_TOKEN)).thenReturn(0);

        service.uncastVote(SESSION_ID, CARD_ID, ACCESS_TOKEN, principal);

        verify(balanceRepository, never()).decrementIfPositive(any(), anyString());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/errors"), any(WsErrorPayload.class));
    }

    // -------------------------------------------------------------------------
    // queryBalance
    // -------------------------------------------------------------------------

    /**
     * Given a participant with an existing balance row, when they query their balance, then the
     * authoritative remaining/allowed counts are sent to them alone.
     */
    @Test
    void queryBalance_existingRow_notifiesCurrentBalance() {
        when(balanceRepository.findBySessionIdAndVoterToken(SESSION_ID, ACCESS_TOKEN))
                .thenReturn(Optional.of(new RetroVoteBalance(SESSION_ID, ACCESS_TOKEN, 2, 3, NOW)));

        service.queryBalance(SESSION_ID, ACCESS_TOKEN, principal);

        ArgumentCaptor<Object> balanceCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/votes"), balanceCaptor.capture());
        VoteBalanceEvent balance = (VoteBalanceEvent) balanceCaptor.getValue();
        assertThat(balance.votesRemaining()).isEqualTo(1);
        assertThat(balance.votesAllowed()).isEqualTo(3);
    }

    /**
     * Given a participant with no balance row yet (never voted), when they query their balance,
     * then they are reported their full allotment — and no row is created merely to answer the
     * query.
     */
    @Test
    void queryBalance_noExistingRow_reportsFullAllotmentWithoutCreatingRow() {
        when(balanceRepository.findBySessionIdAndVoterToken(SESSION_ID, ACCESS_TOKEN)).thenReturn(Optional.empty());

        service.queryBalance(SESSION_ID, ACCESS_TOKEN, principal);

        verify(balanceRepository, never()).ensureBalanceRow(any(), anyString(), anyInt());
        ArgumentCaptor<Object> balanceCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq("connection-1"), eq("/queue/votes"), balanceCaptor.capture());
        VoteBalanceEvent balance = (VoteBalanceEvent) balanceCaptor.getValue();
        assertThat(balance.votesRemaining()).isEqualTo(3);
        assertThat(balance.votesAllowed()).isEqualTo(3);
    }

    private static RetroSession voteSession() {
        RetroSession session = new RetroSession(
                7L, 1L, "Sprint Retro", RetroFormat.START_STOP_CONTINUE, null, null,
                99L, "ABC123", null, null, null, 3,
                NOW.plusSeconds(3600), NOW);
        session.setCurrentPhase(RetroPhase.VOTE);
        return session;
    }

    private static RetroCard cardIn(final UUID sessionId) {
        return new RetroCard(sessionId, "went-well", "Good pace", false, 1L, NOW);
    }
}
