package fr.pivot.agilite.poker.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link PokerTicket} (US09.2.1), schema {@code agilite}.
 */
public interface PokerTicketRepository extends JpaRepository<PokerTicket, UUID> {

    /**
     * Finds the currently open ({@link PokerTicketStatus#VOTING}) ticket for a room, if any —
     * at most one can exist at a time (partial unique database index).
     *
     * @param roomId the owning room's identifier
     * @param status the status to match, always {@link PokerTicketStatus#VOTING} in practice
     * @return the matching ticket, or empty if none is currently open
     */
    Optional<PokerTicket> findByRoomIdAndStatus(UUID roomId, PokerTicketStatus status);

    /**
     * Checks whether a room already has a ticket with the given status — used by {@link
     * PokerTicketService#create} to reject a second concurrent {@link PokerTicketStatus#VOTING}
     * ticket before hitting the database constraint.
     *
     * @param roomId the owning room's identifier
     * @param status the status to check for
     * @return {@code true} if such a ticket already exists
     */
    boolean existsByRoomIdAndStatus(UUID roomId, PokerTicketStatus status);
}
