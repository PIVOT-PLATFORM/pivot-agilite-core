package fr.pivot.agilite.retro.card.dto;

/**
 * Payload sent by a participant submitting a new contribution card (US20.1.2a), over STOMP SEND
 * to {@code /app/agilite/retro/{sessionId}/cards}.
 *
 * <p>Validated manually by {@code RetroCardService} (not Bean Validation annotations — STOMP
 * message handling in this codebase has no established {@code @Valid}/{@code
 * MethodArgumentNotValidException} wiring yet, unlike the REST controllers): a rejected
 * submission is reported to the sender alone via {@code /user/queue/errors}, never broadcast.
 *
 * @param content   the card's free-text content, non-blank, reasonable length
 * @param columnKey the target column key (format-defined; catalogue owned by US20.2.1) —
 *                  non-blank, at most 50 characters (matches the database column width)
 * @param anonymous whether the participant wants this specific card submitted anonymously — the
 *                  author is then never persisted at all (see {@code agilite.retro_cards}'s
 *                  {@code chk_retro_cards_anonymous_no_author} constraint), regardless of whether
 *                  the participant is otherwise authenticated
 */
public record SubmitCardRequest(String content, String columnKey, boolean anonymous) {
}
