package fr.pivot.agilite.poker.vote.dto;

import java.util.UUID;

/**
 * {@code VOTE_CAST} event broadcast to every participant on {@code /topic/agilite/poker/{roomId}}
 * (US09.2.1) — fired after every vote submission or change.
 *
 * <p><strong>Masked by design, for absolutely everyone including the facilitator</strong> —
 * carries only the aggregate {@code votedCount}/{@code totalParticipants}, never the ticket
 * {@code value} nor any per-participant identity. Unlike US20.1.2a's retro card masking (which
 * still exposes a facilitator-only unmasked preview channel), planning poker has no equivalent
 * channel: nobody sees any vote value before US09.2.2's reveal. Proven at the raw STOMP frame
 * level by {@code PokerVoteSubmissionIT}.
 *
 * @param type              always {@code "VOTE_CAST"} — discriminator for the shared room topic,
 *                          which also carries {@code TICKET_CREATED}
 * @param roomId            the room this vote was cast in
 * @param ticketId           the ticket this vote was cast on
 * @param votedCount        the number of distinct participants who have voted on this ticket so far
 * @param totalParticipants the number of participants currently registered in the room (see
 *                          {@code PokerParticipantRegistryService})
 */
public record VoteCastEvent(String type, UUID roomId, UUID ticketId, long votedCount, long totalParticipants) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTE_CAST";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId            the room this vote was cast in
     * @param ticketId          the ticket this vote was cast on
     * @param votedCount        the number of distinct participants who have voted so far
     * @param totalParticipants the number of participants currently registered in the room
     * @return the constructed event
     */
    public static VoteCastEvent of(
            final UUID roomId, final UUID ticketId, final long votedCount, final long totalParticipants) {
        return new VoteCastEvent(TYPE, roomId, ticketId, votedCount, totalParticipants);
    }
}
