package fr.pivot.agilite.retro.phase.dto;

import fr.pivot.agilite.retro.card.dto.RevealedCard;
import fr.pivot.agilite.retro.card.dto.RevealedColumns;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code CARDS_REVEALED} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} when the facilitator triggers the reveal (US20.1.2a).
 *
 * <p>Every card the session has ever received is included, in clear, grouped by column key —
 * columns are ordered by first-submission time, and cards within a column are ordered by
 * submission time. Deliberately never includes authorship (see {@link RevealedCard}'s JavaDoc):
 * even a non-anonymous card's author stays a server-side-only field, never broadcast. This is the
 * exact contract US20.1.2b (dot-voting) builds on — {@code columns} gives it every card id it can
 * attach a vote to.
 *
 * @param type      always {@code "CARDS_REVEALED"} — discriminator for the shared session topic
 * @param sessionId the session that was revealed
 * @param columns   every card, grouped by column key
 */
public record CardsRevealedEvent(String type, UUID sessionId, Map<String, List<RevealedCard>> columns) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "CARDS_REVEALED";

    /**
     * Canonical constructor — stores an unmodifiable, order-preserving copy of {@code columns}
     * rather than the caller-supplied map/lists directly (see {@link RevealedColumns}).
     */
    public CardsRevealedEvent {
        columns = RevealedColumns.immutableCopy(columns);
    }

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId the session that was revealed
     * @param columns   every card, grouped by column key
     * @return the constructed event
     */
    public static CardsRevealedEvent of(final UUID sessionId, final Map<String, List<RevealedCard>> columns) {
        return new CardsRevealedEvent(TYPE, sessionId, columns);
    }
}
