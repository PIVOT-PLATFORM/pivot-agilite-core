package fr.pivot.agilite.retro.vote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Persistence access for {@link RetroVote} (US20.1.2b).
 */
public interface RetroVoteRepository extends JpaRepository<RetroVote, UUID> {

    /**
     * Counts the total number of votes cast on a single card, across every participant — the
     * value broadcast as {@code VOTE_CAST}/{@code VOTE_UNCAST}'s {@code voteCount}.
     *
     * @param cardId the voted-on card's id
     * @return the card's total vote count
     */
    long countByCardId(UUID cardId);

    /**
     * Counts votes per card for an entire session — used to build the vote-count ranking
     * broadcast on the {@code VOTE} → {@code ACTION} transition. Cards with zero votes are simply
     * absent from the result (never a zero-count row); callers must default missing cards to 0.
     *
     * @param sessionId the session to rank
     * @return one projection row per card that has received at least one vote
     */
    @Query("SELECT v.cardId AS cardId, COUNT(v) AS voteCount FROM RetroVote v "
            + "WHERE v.sessionId = :sessionId GROUP BY v.cardId")
    List<CardVoteCount> countVotesBySession(@Param("sessionId") UUID sessionId);

    /**
     * Atomically deletes exactly one vote row matching {@code (cardId, voterToken)} — the oldest
     * one, by {@code created_at} — used to implement uncast (a participant removing a single vote
     * from a card they voted on possibly more than once).
     *
     * @param cardId     the card to remove one vote from
     * @param voterToken the voter whose vote should be removed
     * @return the number of rows deleted (0 or 1)
     */
    @Modifying
    @Query(value = "DELETE FROM agilite.retro_votes WHERE id = ("
            + "SELECT id FROM agilite.retro_votes WHERE card_id = :cardId AND voter_token = :voterToken "
            + "ORDER BY created_at ASC LIMIT 1)", nativeQuery = true)
    int deleteOneVote(@Param("cardId") UUID cardId, @Param("voterToken") String voterToken);

    /**
     * Per-card vote-count projection, grouped by {@code sessionId} (US20.1.2b ranking).
     */
    interface CardVoteCount {

        /**
         * Returns the card this row's count applies to.
         *
         * @return the card's id
         */
        UUID getCardId();

        /**
         * Returns the total number of votes cast on this card.
         *
         * @return the vote count
         */
        long getVoteCount();
    }
}
