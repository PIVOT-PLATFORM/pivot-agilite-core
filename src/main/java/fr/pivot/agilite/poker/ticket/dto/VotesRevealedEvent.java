package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code VOTES_REVEALED} event broadcast to every participant on {@code
 * /topic/agilite/poker/{roomId}} (US09.2.2) — fired once, at the same instant as the REST reveal
 * response, right after a facilitator reveals a ticket's votes.
 *
 * <p>Carries the same {@code values}/{@code consensus} content as {@link RevealResponse} (the
 * REST caller's own response) — simultaneous, identical information for the facilitator and every
 * other room subscriber, never a partial or staggered reveal. {@code values} is anonymous: no
 * {@code participantKey}, no {@code userId}, anywhere in this event.
 *
 * <p>Shape figured for the shared room topic alongside {@code TICKET_CREATED}/{@code VOTE_CAST}
 * (US09.2.1) — this event does not modify either of those.
 *
 * @param type       always {@code "VOTES_REVEALED"} — discriminator for the shared room topic
 * @param roomId     the room this ticket belongs to
 * @param ticketId   the revealed ticket's id
 * @param values     every cast vote's raw value (including {@code "?"}), anonymous, no defined
 *                   order
 * @param consensus  the computed mean/median/majority
 * @param revealedAt revelation timestamp
 */
public record VotesRevealedEvent(
        String type, UUID roomId, UUID ticketId, List<String> values,
        ConsensusResponse consensus, Instant revealedAt) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTES_REVEALED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId     the room this ticket belongs to
     * @param ticketId   the revealed ticket's id
     * @param values     every cast vote's raw value, anonymous
     * @param consensus  the computed mean/median/majority
     * @param revealedAt revelation timestamp
     * @return the constructed event
     */
    public static VotesRevealedEvent of(
            final UUID roomId, final UUID ticketId, final List<String> values,
            final ConsensusResponse consensus, final Instant revealedAt) {
        return new VotesRevealedEvent(TYPE, roomId, ticketId, values, consensus, revealedAt);
    }
}
