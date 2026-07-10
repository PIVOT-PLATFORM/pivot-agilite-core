package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.poker.PokerRoom;
import fr.pivot.agilite.poker.PokerRoomRepository;
import fr.pivot.agilite.poker.exception.ActiveTicketExistsException;
import fr.pivot.agilite.poker.exception.TicketFacilitatorOnlyException;
import fr.pivot.agilite.poker.exception.RoomNotFoundException;
import fr.pivot.agilite.poker.ticket.dto.TicketCreatedEvent;
import fr.pivot.agilite.poker.ticket.dto.TicketResponse;
import fr.pivot.agilite.poker.ws.PokerRoomDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for planning poker ticket creation and current-ticket lookup (US09.2.1).
 */
@Service
public class PokerTicketService {

    private final PokerTicketRepository ticketRepository;
    private final PokerRoomRepository roomRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param ticketRepository  ticket persistence
     * @param roomRepository    room persistence, used to resolve the room and check the
     *                          facilitator identity
     * @param messagingTemplate used to broadcast {@code TICKET_CREATED} events
     * @param clock             the shared clock, overridable in tests
     */
    public PokerTicketService(
            final PokerTicketRepository ticketRepository,
            final PokerRoomRepository roomRepository,
            final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.ticketRepository = ticketRepository;
        this.roomRepository = roomRepository;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Creates a new ticket in a room, restricted to that room's facilitator, and broadcasts the
     * {@code TICKET_CREATED} event to every participant.
     *
     * @param roomId            the target room, scoped to the caller's tenant
     * @param title             the ticket's display title (already validated by the controller)
     * @param callerUserId      the caller's user id, resolved server-side from the bearer token
     * @param tenantId          the caller's tenant id, resolved server-side from the bearer token
     * @return the created ticket
     * @throws RoomNotFoundException          if the room does not exist for the caller's tenant
     * @throws TicketFacilitatorOnlyException  if the caller is not the room's facilitator
     * @throws ActiveTicketExistsException    if the room already has a {@code VOTING} ticket
     */
    @Transactional
    public TicketResponse create(
            final UUID roomId, final String title, final Long callerUserId, final Long tenantId) {
        PokerRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        if (!room.getFacilitatorUserId().equals(callerUserId)) {
            throw new TicketFacilitatorOnlyException(roomId);
        }
        if (ticketRepository.existsByRoomIdAndStatus(roomId, PokerTicketStatus.VOTING)) {
            throw new ActiveTicketExistsException(roomId);
        }

        PokerTicket ticket = new PokerTicket(roomId, title, clock.instant());
        PokerTicket saved = ticketRepository.save(ticket);

        messagingTemplate.convertAndSend(
                PokerRoomDestinations.roomTopic(roomId),
                (Object) TicketCreatedEvent.of(roomId, saved.getId(), saved.getTitle(), saved.getCreatedAt()));

        return toResponse(saved);
    }

    /**
     * Finds the currently open ({@code VOTING}) ticket for a room, if any.
     *
     * @param roomId   the target room, scoped to the caller's tenant
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the open ticket, or empty if none is currently open
     * @throws RoomNotFoundException if the room does not exist for the caller's tenant
     */
    @Transactional(readOnly = true)
    public Optional<TicketResponse> getCurrent(final UUID roomId, final Long tenantId) {
        roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));
        return ticketRepository.findByRoomIdAndStatus(roomId, PokerTicketStatus.VOTING)
                .map(PokerTicketService::toResponse);
    }

    /**
     * Maps a persisted ticket to its API response shape.
     *
     * @param ticket the persisted ticket
     * @return the corresponding {@link TicketResponse}
     */
    private static TicketResponse toResponse(final PokerTicket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getRoomId(),
                ticket.getTitle(),
                ticket.getStatus().name(),
                ticket.getCreatedAt());
    }
}
