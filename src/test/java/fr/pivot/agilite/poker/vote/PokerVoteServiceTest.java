package fr.pivot.agilite.poker.vote;

import fr.pivot.agilite.poker.ticket.PokerTicket;
import fr.pivot.agilite.poker.ticket.PokerTicketRepository;
import fr.pivot.agilite.poker.vote.dto.SubmitVoteRequest;
import fr.pivot.agilite.poker.ws.PokerParticipantRegistryService;
import fr.pivot.agilite.ws.WsErrorPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PokerVoteService} (US09.2.1).
 */
@ExtendWith(MockitoExtension.class)
class PokerVoteServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-10T10:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ROOM_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACCESS_TOKEN = "token-abc";

    @Mock
    private PokerVoteRepository voteRepository;

    @Mock
    private PokerTicketRepository ticketRepository;

    @Mock
    private PokerParticipantRegistryService participantRegistryService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private Principal principal;

    private PokerVoteService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new PokerVoteService(
                voteRepository, ticketRepository, participantRegistryService, messagingTemplate, fixedClock);
    }

    /**
     * Given a valid open ticket and a Fibonacci card value, when a vote is submitted for the
     * first time, then it is persisted and a masked {@code VOTE_CAST} event — carrying only
     * {@code votedCount}/{@code totalParticipants}, never the value — is broadcast to the room
     * topic.
     */
    @Test
    void submit_newVoteOnOpenTicket_persistsAndBroadcastsMaskedEvent() {
        PokerTicket ticket = votingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(voteRepository.findByTicketIdAndParticipantKey(eq(TICKET_ID), any())).thenReturn(Optional.empty());
        when(voteRepository.countByTicketId(TICKET_ID)).thenReturn(1L);
        when(participantRegistryService.countActive(ROOM_ID)).thenReturn(4L);

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "5"), ACCESS_TOKEN, principal);

        verify(voteRepository).save(any(PokerVote.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/agilite/poker/" + ROOM_ID), any(Object.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    /**
     * Given a participant who already voted on the open ticket, when they submit a different
     * value, then the existing row is updated (no new row inserted) and {@code votedCount} in the
     * rebroadcast is unaffected by the change itself (recomputed from the same row count).
     */
    @Test
    void submit_changeVote_updatesExistingRowInsteadOfInserting() {
        PokerTicket ticket = votingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        PokerVote existingVote = new PokerVote(TICKET_ID, "irrelevant-hash-value-not-asserted", "3", FIXED_NOW);
        when(voteRepository.findByTicketIdAndParticipantKey(eq(TICKET_ID), any()))
                .thenReturn(Optional.of(existingVote));
        when(voteRepository.countByTicketId(TICKET_ID)).thenReturn(1L);
        when(participantRegistryService.countActive(ROOM_ID)).thenReturn(4L);

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "8"), ACCESS_TOKEN, principal);

        assertThat(existingVote.getValue()).isEqualTo("8");
        verify(voteRepository, never()).save(any());
    }

    /**
     * Error case: given a {@code ticketId} that does not exist at all, when a vote is submitted,
     * then it is rejected with an error notification to the sender alone — no broadcast.
     */
    @Test
    void submit_unknownTicket_notifiesErrorWithoutBroadcast() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());
        when(principal.getName()).thenReturn("session-1");

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "5"), ACCESS_TOKEN, principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(messagingTemplate).convertAndSendToUser(eq("session-1"), eq("/queue/errors"), any(WsErrorPayload.class));
        verify(voteRepository, never()).save(any());
    }

    /**
     * Security AC (cross-room isolation): given a valid ticket that belongs to a different room
     * than the STOMP destination's, when a vote is submitted, then it is rejected identically to
     * an unknown ticket — never confirming the ticket's existence in another room.
     */
    @Test
    void submit_ticketBelongsToDifferentRoom_notifiesErrorWithoutBroadcast() {
        PokerTicket ticket = new PokerTicket(OTHER_ROOM_ID, "Title", FIXED_NOW);
        setId(ticket, TICKET_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(principal.getName()).thenReturn("session-1");

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "5"), ACCESS_TOKEN, principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(voteRepository, never()).save(any());
    }

    /**
     * Error case: given a ticket that has already been revealed, when a vote is submitted, then
     * it is rejected — voting is closed once revealed.
     */
    @Test
    void submit_revealedTicket_notifiesErrorWithoutBroadcast() {
        PokerTicket ticket = revealedTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(principal.getName()).thenReturn("session-1");

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "5"), ACCESS_TOKEN, principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(voteRepository, never()).save(any());
    }

    /**
     * Error case: given a {@code value} that is not part of the fixed Fibonacci deck, when a
     * vote is submitted, then it is rejected without persistence or broadcast.
     */
    @Test
    void submit_invalidCardValue_notifiesErrorWithoutBroadcast() {
        PokerTicket ticket = votingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(principal.getName()).thenReturn("session-1");

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "42"), ACCESS_TOKEN, principal);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(voteRepository, never()).save(any());
    }

    /**
     * Security AC: given two distinct access tokens voting on the same ticket, when both submit,
     * then each is stored under a distinct, hashed {@code participantKey} — never the raw token.
     */
    @Test
    void submit_persistedParticipantKeyIsHashedNeverRawToken() {
        PokerTicket ticket = votingTicket();
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(voteRepository.findByTicketIdAndParticipantKey(eq(TICKET_ID), any())).thenReturn(Optional.empty());
        when(voteRepository.countByTicketId(TICKET_ID)).thenReturn(1L);
        when(participantRegistryService.countActive(ROOM_ID)).thenReturn(1L);

        service.submit(ROOM_ID, new SubmitVoteRequest(TICKET_ID, "5"), ACCESS_TOKEN, principal);

        org.mockito.ArgumentCaptor<PokerVote> captor = org.mockito.ArgumentCaptor.forClass(PokerVote.class);
        verify(voteRepository).save(captor.capture());
        String persistedKey = captor.getValue().getParticipantKey();
        assertThat(persistedKey).isNotEqualTo(ACCESS_TOKEN);
        assertThat(persistedKey).hasSize(64); // SHA-256 hex digest length
        assertThat(persistedKey).matches("[0-9a-f]{64}");
    }

    private static PokerTicket votingTicket() {
        PokerTicket ticket = new PokerTicket(ROOM_ID, "Title", FIXED_NOW);
        setId(ticket, TICKET_ID);
        return ticket;
    }

    private static PokerTicket revealedTicket() {
        PokerTicket ticket = votingTicket();
        try {
            java.lang.reflect.Field field = PokerTicket.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(ticket, fr.pivot.agilite.poker.ticket.PokerTicketStatus.REVEALED);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return ticket;
    }

    private static void setId(final PokerTicket ticket, final UUID id) {
        try {
            java.lang.reflect.Field field = PokerTicket.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(ticket, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
