package fr.pivot.agilite.retro.card.dto;

import java.util.UUID;

/**
 * {@code CARD_ADDED} event broadcast to every participant on
 * {@code /topic/agilite/retro/{sessionId}} (US20.1.2a).
 *
 * <p><strong>Masked by design</strong> — carries only the current card count for the target
 * column, never the card's {@code id} or {@code content}. This is the payload the AC's Test TI
 * inspects at the raw frame level to prove no content ever transits to non-facilitator
 * participants before {@code CARDS_REVEALED}. See {@link CardAddedFacilitatorEvent} for the
 * unmasked counterpart delivered only on the facilitator-only topic.
 *
 * @param type      always {@code "CARD_ADDED"} — discriminator for the shared session topic,
 *                  which also carries {@code PHASE_CHANGED} and {@code CARDS_REVEALED} events
 * @param sessionId the session this card was submitted to
 * @param columnKey the target column key
 * @param cardCount the total number of cards currently submitted to this column
 */
public record CardAddedMaskedEvent(String type, UUID sessionId, String columnKey, long cardCount) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "CARD_ADDED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId the session this card was submitted to
     * @param columnKey the target column key
     * @param cardCount the total number of cards currently submitted to this column
     * @return the constructed event
     */
    public static CardAddedMaskedEvent of(final UUID sessionId, final String columnKey, final long cardCount) {
        return new CardAddedMaskedEvent(TYPE, sessionId, columnKey, cardCount);
    }
}
