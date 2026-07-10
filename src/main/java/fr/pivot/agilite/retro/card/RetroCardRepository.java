package fr.pivot.agilite.retro.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence access for {@link RetroCard} (US20.1.2a).
 */
public interface RetroCardRepository extends JpaRepository<RetroCard, UUID> {

    /**
     * Finds every card submitted to a session, oldest first — used both to build the
     * {@code CARDS_REVEALED} broadcast (grouped by column afterward) and by tests.
     *
     * @param sessionId the owning session's id
     * @return the session's cards in submission order
     */
    List<RetroCard> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Counts the cards currently submitted to a session's column — the number broadcast as the
     * masked {@code CARD_ADDED} count, never the content.
     *
     * @param sessionId the owning session's id
     * @param columnKey the target column key
     * @return the current card count for this session/column pair
     */
    long countBySessionIdAndColumnKey(UUID sessionId, String columnKey);
}
