package fr.pivot.agilite.poker.vote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link PokerVote} (US09.2.1), schema {@code agilite}.
 */
public interface PokerVoteRepository extends JpaRepository<PokerVote, UUID> {

    /**
     * Finds a participant's existing vote on a ticket, if any — used to implement upsert
     * semantics (change of vote before reveal).
     *
     * @param ticketId       the voted-on ticket's identifier
     * @param participantKey the SHA-256 hex digest identifying the voter
     * @return the existing vote, or empty if this participant has not yet voted on this ticket
     */
    Optional<PokerVote> findByTicketIdAndParticipantKey(UUID ticketId, String participantKey);

    /**
     * Counts the distinct participants who have voted on a ticket — the masked {@code
     * votedCount} broadcast in every {@code VOTE_CAST} event.
     *
     * @param ticketId the ticket's identifier
     * @return the number of distinct votes recorded for this ticket
     */
    long countByTicketId(UUID ticketId);
}
