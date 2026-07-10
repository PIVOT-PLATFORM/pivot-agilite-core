package fr.pivot.agilite.retro.card.dto;

import java.util.UUID;

/**
 * {@code CARD_ADDED} event broadcast <strong>only</strong> to the session's facilitator, on
 * {@code /topic/agilite/retro/{sessionId}/facilitator} (US20.1.2a).
 *
 * <p>Carries the full, unmasked card content — the facilitator preview channel this US's AC
 * requires ("aucun participant autre que l'animateur" sees content in clear before {@code
 * CARDS_REVEALED}). Subscription to this destination is restricted to a facilitator-flagged
 * access grant by {@code RetroChannelInterceptor}; never confuse it with {@link
 * CardAddedMaskedEvent}, broadcast to every participant on the regular session topic.
 *
 * <p>Never carries authorship — even for a non-anonymous card — consistent with this US's AC
 * wording (only content/column are ever broadcast); {@code author_user_id} stays a
 * server-side-only field of {@code agilite.retro_cards}, never exposed over any STOMP channel.
 *
 * @param type      always {@code "CARD_ADDED"}
 * @param sessionId the session this card was submitted to
 * @param cardId    the persisted card's id
 * @param columnKey the target column key
 * @param content   the card's full, unmasked content
 * @param anonymous whether the participant chose to submit this card anonymously
 */
public record CardAddedFacilitatorEvent(
        String type, UUID sessionId, UUID cardId, String columnKey, String content, boolean anonymous) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "CARD_ADDED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param sessionId the session this card was submitted to
     * @param cardId    the persisted card's id
     * @param columnKey the target column key
     * @param content   the card's full, unmasked content
     * @param anonymous whether the participant chose to submit this card anonymously
     * @return the constructed event
     */
    public static CardAddedFacilitatorEvent of(
            final UUID sessionId, final UUID cardId, final String columnKey,
            final String content, final boolean anonymous) {
        return new CardAddedFacilitatorEvent(TYPE, sessionId, cardId, columnKey, content, anonymous);
    }
}
