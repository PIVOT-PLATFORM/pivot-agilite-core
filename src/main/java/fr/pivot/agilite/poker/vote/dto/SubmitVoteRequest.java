package fr.pivot.agilite.poker.vote.dto;

import java.util.UUID;

/**
 * Payload sent by a participant submitting (or changing) a vote (US09.2.1), over STOMP SEND to
 * {@code /app/agilite/poker/{roomId}/vote}.
 *
 * <p>Validated manually by {@code PokerVoteService} (not Bean Validation annotations — STOMP
 * message handling in this codebase has no established {@code @Valid} wiring, same convention as
 * {@code SubmitCardRequest}/US20.1.2a): a rejected submission is reported to the sender alone via
 * {@code /user/queue/errors}, never broadcast.
 *
 * @param ticketId the ticket being voted on — must belong to the room the destination targets
 * @param value    the chosen card value — must be one of {@code PokerCardDeck#FIBONACCI_VALUES}
 */
public record SubmitVoteRequest(UUID ticketId, String value) {
}
