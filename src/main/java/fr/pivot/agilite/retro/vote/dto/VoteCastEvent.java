package fr.pivot.agilite.retro.vote.dto;

import java.util.UUID;

/**
 * {@code VOTE_CAST} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} whenever a dot-vote is successfully cast (US20.1.2b).
 *
 * <p><strong>Never carries voter identity</strong> — only the card's new aggregate vote count,
 * exactly like {@link fr.pivot.agilite.retro.card.dto.CardAddedMaskedEvent}'s masking rationale
 * for card content. The acting participant's own updated balance is delivered separately, only to
 * them, via {@link VoteBalanceEvent} on their private {@code /user/queue/votes}.
 *
 * @param type      always {@code "VOTE_CAST"} — discriminator for the shared session topic
 * @param sessionId the session the vote was cast in
 * @param cardId    the card that was voted on
 * @param voteCount the card's new total vote count, across every participant
 */
public record VoteCastEvent(String type, UUID sessionId, UUID cardId, long voteCount) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTE_CAST";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId the session the vote was cast in
     * @param cardId    the card that was voted on
     * @param voteCount the card's new total vote count
     * @return the constructed event
     */
    public static VoteCastEvent of(final UUID sessionId, final UUID cardId, final long voteCount) {
        return new VoteCastEvent(TYPE, sessionId, cardId, voteCount);
    }
}
