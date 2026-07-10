package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.PokerRoom;
import fr.pivot.agilite.poker.PokerRoomRepository;
import fr.pivot.agilite.poker.exception.ActiveTicketExistsException;
import fr.pivot.agilite.poker.exception.TicketFacilitatorOnlyException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import fr.pivot.agilite.poker.ticket.dto.TicketResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PokerTicketService} (US09.2.1).
 */
@ExtendWith(MockitoExtension.class)
class PokerTicketServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-10T10:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Long FACILITATOR_USER_ID = 7L;
    private static final Long TENANT_ID = 3L;

    @Mock
    private PokerTicketRepository ticketRepository;

    @Mock
    private PokerRoomRepository roomRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private PokerTicketService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new PokerTicketService(ticketRepository, roomRepository, messagingTemplate, fixedClock);
    }

    /**
     * Given the room's facilitator and no currently open ticket, when a ticket is created, then
     * it is persisted as {@code VOTING} and a {@code TICKET_CREATED} event is broadcast to the
     * room topic.
     */
    @Test
    void create_asFacilitatorWithNoActiveTicket_createsAndBroadcasts() {
        PokerRoom room = facilitatorRoom();
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.of(room));
        when(ticketRepository.existsByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING)).thenReturn(false);
        when(ticketRepository.save(any(PokerTicket.class))).thenAnswer(invocation -> {
            PokerTicket ticket = invocation.getArgument(0);
            setId(ticket, TICKET_ID);
            return ticket;
        });

        TicketResponse response = service.create(ROOM_ID, "Estimate JIRA-123", FACILITATOR_USER_ID, TENANT_ID);

        assertThat(response.id()).isEqualTo(TICKET_ID);
        assertThat(response.roomId()).isEqualTo(ROOM_ID);
        assertThat(response.title()).isEqualTo("Estimate JIRA-123");
        assertThat(response.status()).isEqualTo("VOTING");
        assertThat(response.createdAt()).isEqualTo(FIXED_NOW);

        verify(messagingTemplate).convertAndSend(eq("/topic/agilite/poker/" + ROOM_ID), any(Object.class));
    }

    /**
     * Error case: given a room that does not exist for the caller's tenant, when a ticket is
     * created, then {@link RoomNotFoundException} is thrown and nothing is persisted or broadcast.
     */
    @Test
    void create_roomNotFoundForTenant_throwsRoomNotFoundException() {
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(ROOM_ID, "Title", FACILITATOR_USER_ID, TENANT_ID))
                .isInstanceOf(RoomNotFoundException.class);
        verify(ticketRepository, never()).save(any());
    }

    /**
     * Security AC: given an authenticated, same-tenant caller who is not the room's facilitator,
     * when a ticket is created, then {@link TicketFacilitatorOnlyException} is thrown and nothing
     * is persisted or broadcast.
     */
    @Test
    void create_callerNotFacilitator_throwsTicketFacilitatorOnlyException() {
        PokerRoom room = facilitatorRoom();
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.create(ROOM_ID, "Title", 999L, TENANT_ID))
                .isInstanceOf(TicketFacilitatorOnlyException.class);
        verify(ticketRepository, never()).save(any());
    }

    /**
     * Error case: given a room that already has a {@code VOTING} ticket, when the facilitator
     * attempts to create another one, then {@link ActiveTicketExistsException} is thrown and
     * nothing new is persisted or broadcast.
     */
    @Test
    void create_activeTicketAlreadyExists_throwsActiveTicketExistsException() {
        PokerRoom room = facilitatorRoom();
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.of(room));
        when(ticketRepository.existsByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING)).thenReturn(true);

        assertThatThrownBy(() -> service.create(ROOM_ID, "Title", FACILITATOR_USER_ID, TENANT_ID))
                .isInstanceOf(ActiveTicketExistsException.class);
        verify(ticketRepository, never()).save(any());
    }

    /**
     * Given a room with a currently open ticket, when it is looked up, then the ticket is
     * returned.
     */
    @Test
    void getCurrent_activeTicketExists_returnsIt() {
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.of(facilitatorRoom()));
        PokerTicket ticket = new PokerTicket(ROOM_ID, "Title", FIXED_NOW);
        setId(ticket, TICKET_ID);
        when(ticketRepository.findByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING))
                .thenReturn(Optional.of(ticket));

        Optional<TicketResponse> response = service.getCurrent(ROOM_ID, TENANT_ID);

        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(TICKET_ID);
    }

    /**
     * Given a room with no currently open ticket, when it is looked up, then an empty result is
     * returned — not an error, a legitimate room state before the first ticket.
     */
    @Test
    void getCurrent_noActiveTicket_returnsEmpty() {
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.of(facilitatorRoom()));
        when(ticketRepository.findByRoomIdAndStatus(ROOM_ID, PokerTicketStatus.VOTING))
                .thenReturn(Optional.empty());

        assertThat(service.getCurrent(ROOM_ID, TENANT_ID)).isEmpty();
    }

    /**
     * Security AC: given a room belonging to another tenant, when the current ticket is looked
     * up, then {@link RoomNotFoundException} is thrown — never confirms cross-tenant existence.
     */
    @Test
    void getCurrent_crossTenantRoom_throwsRoomNotFoundException() {
        when(roomRepository.findByIdAndTenantId(ROOM_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrent(ROOM_ID, TENANT_ID))
                .isInstanceOf(RoomNotFoundException.class);
    }

    private static PokerRoom facilitatorRoom() {
        PokerRoom room = new PokerRoom(
                TENANT_ID, FACILITATOR_USER_ID, "Room", "ABC234", FIXED_NOW, FIXED_NOW.plusSeconds(3600));
        setId(room, ROOM_ID);
        return room;
    }

    private static void setId(final PokerRoom room, final UUID id) {
        try {
            java.lang.reflect.Field field = PokerRoom.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(room, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
