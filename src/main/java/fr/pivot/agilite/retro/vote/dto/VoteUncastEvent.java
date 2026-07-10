package fr.pivot.agilite.retro.vote.dto;

import java.util.UUID;

/**
 * {@code VOTE_UNCAST} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} whenever a previously cast dot-vote is removed
 * (US20.1.2b — cast/uncast extension, see {@code RetroVoteService}'s class JavaDoc scope note).
 *
 * <p>Symmetric with {@link VoteCastEvent} — never carries voter identity, only the card's new
 * aggregate vote count.
 *
 * @param type      always {@code "VOTE_UNCAST"} — discriminator for the shared session topic
 * @param sessionId the session the vote was removed from
 * @param cardId    the card the vote was removed from
 * @param voteCount the card's new total vote count, across every participant
 */
public record VoteUncastEvent(String type, UUID sessionId, UUID cardId, long voteCount) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTE_UNCAST";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId the session the vote was removed from
     * @param cardId    the card the vote was removed from
     * @param voteCount the card's new total vote count
     * @return the constructed event
     */
    public static VoteUncastEvent of(final UUID sessionId, final UUID cardId, final long voteCount) {
        return new VoteUncastEvent(TYPE, sessionId, cardId, voteCount);
    }
}
