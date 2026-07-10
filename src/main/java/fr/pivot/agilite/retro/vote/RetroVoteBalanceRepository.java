package fr.pivot.agilite.retro.vote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence access for {@link RetroVoteBalance} (US20.1.2b).
 *
 * <p><strong>Race-free by construction.</strong> {@link #incrementIfAvailable}/{@link
 * #decrementIfPositive} are single, guarded atomic {@code UPDATE} statements relying on
 * PostgreSQL's row-level lock taken during the {@code UPDATE} itself — no {@code @Version}/
 * optimistic-retry loop and no explicit {@code SELECT ... FOR UPDATE} are needed. This is the
 * mechanism the US20.1.2b AC's required concurrency test proves holds under genuine concurrent
 * contention (see {@code RetroVoteConcurrencyIT}).
 */
public interface RetroVoteBalanceRepository extends JpaRepository<RetroVoteBalance, UUID> {

    /**
     * Finds the balance row for a given session/participant pair, if one has been created yet.
     *
     * @param sessionId  the session's id
     * @param voterToken the participant's opaque access-token identity
     * @return the balance row, or empty if the participant has not attempted a vote yet
     */
    Optional<RetroVoteBalance> findBySessionIdAndVoterToken(UUID sessionId, String voterToken);

    /**
     * Creates the balance row for a session/participant pair if it does not already exist,
     * leaving an existing row untouched (idempotent — safe to call before every cast attempt).
     *
     * @param sessionId    the session's id
     * @param voterToken   the participant's opaque access-token identity
     * @param votesAllowed the session's configured {@code voteCountPerParticipant}, used only if
     *                     a new row is actually inserted
     */
    @Modifying
    @Query(value = "INSERT INTO agilite.retro_vote_balances (session_id, voter_token, votes_allowed) "
            + "VALUES (:sessionId, :voterToken, :votesAllowed) "
            + "ON CONFLICT (session_id, voter_token) DO NOTHING", nativeQuery = true)
    void ensureBalanceRow(
            @Param("sessionId") UUID sessionId,
            @Param("voterToken") String voterToken,
            @Param("votesAllowed") int votesAllowed);

    /**
     * Atomically increments {@code votesUsed} by one, but only if doing so would not exceed
     * {@code votesAllowed} — the sole write path used by a vote cast.
     *
     * @param sessionId  the session's id
     * @param voterToken the participant's opaque access-token identity
     * @return {@code 1} if the increment was applied, {@code 0} if the balance was already
     *     exhausted (no row is ever left in a negative or over-allotment state)
     */
    @Modifying
    @Query(value = "UPDATE agilite.retro_vote_balances SET votes_used = votes_used + 1, updated_at = now() "
            + "WHERE session_id = :sessionId AND voter_token = :voterToken AND votes_used < votes_allowed",
            nativeQuery = true)
    int incrementIfAvailable(@Param("sessionId") UUID sessionId, @Param("voterToken") String voterToken);

    /**
     * Atomically decrements {@code votesUsed} by one, but only if it is currently positive — the
     * sole write path used by a vote uncast.
     *
     * @param sessionId  the session's id
     * @param voterToken the participant's opaque access-token identity
     * @return {@code 1} if the decrement was applied, {@code 0} if the balance was already at
     *     zero
     */
    @Modifying
    @Query(value = "UPDATE agilite.retro_vote_balances SET votes_used = votes_used - 1, updated_at = now() "
            + "WHERE session_id = :sessionId AND voter_token = :voterToken AND votes_used > 0",
            nativeQuery = true)
    int decrementIfPositive(@Param("sessionId") UUID sessionId, @Param("voterToken") String voterToken);
}
