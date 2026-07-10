package fr.pivot.agilite.retro.vote.dto;

import java.util.UUID;

/**
 * {@code VOTE_BALANCE} event sent privately to a single participant, on their own
 * {@code /user/queue/votes}, after a cast/uncast/balance-query (US20.1.2b).
 *
 * <p><strong>Never broadcast to the room topic</strong> — a participant's remaining vote count is
 * only ever their own business, exactly like {@code RetroCardService}'s {@code /queue/errors}
 * pattern for rejection notifications. The server is the sole source of truth for these numbers
 * (AC: "le décompte de votes restants est autoritaire côté serveur").
 *
 * @param type            always {@code "VOTE_BALANCE"} — discriminator for the private user queue
 * @param sessionId       the session this balance applies to
 * @param votesRemaining  the number of votes this participant may still cast
 * @param votesAllowed    the total number of votes originally allotted to this participant
 */
public record VoteBalanceEvent(String type, UUID sessionId, int votesRemaining, int votesAllowed) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTE_BALANCE";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId      the session this balance applies to
     * @param votesRemaining the number of votes this participant may still cast
     * @param votesAllowed   the total number of votes originally allotted to this participant
     * @return the constructed event
     */
    public static VoteBalanceEvent of(
            final UUID sessionId, final int votesRemaining, final int votesAllowed) {
        return new VoteBalanceEvent(TYPE, sessionId, votesRemaining, votesAllowed);
    }
}
