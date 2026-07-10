package fr.pivot.agilite.retro.vote.dto;

import java.util.UUID;

/**
 * A single card's shape in the vote-count ranking broadcast on the {@code VOTE} → {@code ACTION}
 * transition (US20.1.2b), as part of {@code PhaseChangedEvent#rankedCards}.
 *
 * @param cardId    the card's identifier
 * @param columnKey the card's format-defined column key
 * @param content   the card's full content
 * @param voteCount the total number of votes this card received, across every participant
 */
public record RankedCard(UUID cardId, String columnKey, String content, long voteCount) {
}
